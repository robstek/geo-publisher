package models;

import static akka.pattern.Patterns.ask;
import static play.libs.F.Promise.sequence;
import static play.libs.F.Promise.wrap;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nl.idgis.publisher.domain.MessageProperties;
import nl.idgis.publisher.domain.query.DeleteEntity;
import nl.idgis.publisher.domain.query.DomainQuery;
import nl.idgis.publisher.domain.query.GetEntity;
import nl.idgis.publisher.domain.query.ListEntity;
import nl.idgis.publisher.domain.query.PutEntity;
import nl.idgis.publisher.domain.response.Page;
import nl.idgis.publisher.domain.response.Response;
import nl.idgis.publisher.domain.web.Entity;
import nl.idgis.publisher.domain.web.EntityType;
import nl.idgis.publisher.domain.web.Identifiable;
import nl.idgis.publisher.domain.web.Message;
import nl.idgis.publisher.domain.web.MessageContext;
import nl.idgis.publisher.domain.web.NotFound;
import nl.idgis.publisher.domain.web.Status;
import play.Logger;
import play.i18n.Lang;
import play.i18n.Messages;
import play.libs.F;
import play.libs.F.Promise;
import akka.actor.ActorSelection;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

public class Domain {

	public static DomainInstance from (final ActorSelection domainActor) {
		return from (domainActor, 15000);
	}
	
	public static DomainInstance from (final ActorSelection domainActor, final int timeout) {
		return new DomainInstance (domainActor, timeout);
	}
	
	public static interface Query {
	}
	
	public static interface Queryable {
		<T extends Entity> Query get (Class<T> cls, String id);
		<T extends Entity> Query list (Class<T> cls);
		<T extends Entity> Query list (Class<T> cls, long page);
		
		<T extends Identifiable> Query put (T value);
		<T extends Identifiable> Query delete (Class<? extends T> cls, String id);
		
		<T> Query query (DomainQuery<T> domainQuery);
	}
	
	public final static class DomainInstance implements Queryable {
		protected final ActorSelection domainActor;
		protected final int timeout;
		
		public DomainInstance (final ActorSelection domainActor, final int timeout) {
			if (domainActor == null) {
				throw new NullPointerException ("domainActor cannot be null");
			}
			
			this.domainActor = domainActor;
			this.timeout = timeout;
		}
		
		public ActorSelection domainActor () {
			return this.domainActor;
		}

		@Override
		public <T extends Entity> Query1<T> get (final Class<T> cls, final String id) {
			return query (id == null ? null : new GetEntity<> (cls, id));
		}

		@Override
		public <T extends Entity> Query1<Page<T>> list (Class<T> cls) {
			return query (new ListEntity<> (cls, 0));
		}
		
		@Override
		public <T extends Entity> Query1<Page<T>> list (Class<T> cls, long page) {
			return query (new ListEntity<> (cls, page));
		}
		
		@Override
		public <T extends Identifiable> Query1<Response<?>> put (final T value) {
			return query (new PutEntity<> (value));
		}
		
		@Override
		public <T extends Identifiable> Query1<Response<?>> delete (final Class<? extends T> cls, final String id) {
			return query (new DeleteEntity<> (cls, id));
		}

		@Override
		public <T> Query1<T> query (final DomainQuery<T> domainQuery) {
			return new Query1<T> (this, domainQuery);
		}
	}

	public final static class Query1<A> implements Query, Queryable {

		protected final DomainInstance domainInstance;
		protected final DomainQuery<A> query;
		
		public Query1 (final DomainInstance domainInstance, final DomainQuery<A> query) {
			this.domainInstance = domainInstance;
			this.query = query;
		}
		
		@Override
		public <T extends Entity> Query2<A, T> get (final Class<T> cls, final String id) {
			return query (id == null ? null : new GetEntity<> (cls, id));
		}
		
		@Override
		public <T extends Entity> Query2<A, Page<T>> list (final Class<T> cls) {
			return query (new ListEntity<> (cls, 0));
		}
		
		@Override
		public <T extends Entity> Query2<A, Page<T>> list (final Class<T> cls, long page) {
			return query (new ListEntity<> (cls, page));
		}
		
		@Override
		public <T extends Identifiable> Query2<A, Response<?>> put (final T value) {
			return query (new PutEntity<> (value));
		}
		
		@Override
		public <T extends Identifiable> Query2<A, Response<?>> delete (final Class<? extends T> cls, final String id) {
			return query (new DeleteEntity<> (cls, id));
		}
		
		@Override
		public <T> Query2<A, T> query (final DomainQuery<T> domainQuery) {
			return new Query2<A, T> (this, domainQuery);
		}

		private Promise<Object> promise () {
			return
					askDomain (
						domainInstance.domainActor, 
						query, 
						domainInstance.timeout
					);
		}
		
		public <R> Promise<R> execute (final Function<A, R> callback) {
			return promise ()
				.map (new F.Function<Object, R> () {
					@Override
					public R apply (final Object a) throws Throwable {
						@SuppressWarnings("unchecked")
						final A value = (A)a;
						return callback.apply (value);
					}
				});
		}
		
		public <R> Promise<R> executeFlat (final Function<A, Promise<R>> callback) {
			return promise ()
				.flatMap (new F.Function<Object, F.Promise<R>> () {
					@Override
					public Promise<R> apply (final Object a) throws Throwable {
						@SuppressWarnings("unchecked")
						final A value = (A)a;
						return callback.apply (value);
					}
				});
		}
		
		public <R> Promise<R> execute (final Function<A, R> callback, final Function<Throwable, R> errorCallback) {
			return execute (callback).recover (new F.Function<Throwable, R> () {
					@Override
					public R apply (final Throwable a) throws Throwable {
						return errorCallback.apply (a);
					}
				});
		}
	}
	
	public final static class Query2<A, B> implements Query, Queryable {

		protected final Query1<A> baseQuery;
		protected final DomainQuery<B> query;
		
		public Query2 (final Query1<A> baseQuery, final DomainQuery<B> query) {
			this.baseQuery = baseQuery;
			this.query = query;
		}
		
		@Override
		public <T extends Entity> Query3<A, B, T> get (final Class<T> cls, final String id) {
			return query (id == null ? null : new GetEntity<> (cls, id));
		}
		
		@Override
		public <T extends Entity> Query3<A, B, Page<T>> list (final Class<T> cls) {
			return query (new ListEntity<> (cls, 0));
		}
		
		@Override
		public <T extends Entity> Query3<A, B, Page<T>> list (final Class<T> cls, long page) {
			return query (new ListEntity<> (cls, page));
		}
		
		@Override
		public <T extends Identifiable> Query3<A, B, Response<?>> put (final T value) {
			return query (new PutEntity<> (value));
		}
		
		@Override
		public <T extends Identifiable> Query3<A, B, Response<?>> delete (final Class<? extends T> cls, final String id) {
			return query (new DeleteEntity<> (cls, id));
		}
		
		@Override
		public <T> Query3<A, B, T> query (final DomainQuery<T> domainQuery) {
			return new Query3<> (this, domainQuery);
		}
		
		private List<Promise<Object>> promises () {
			final List<Promise<Object>> promises = new ArrayList<> ();
			promises.add (
					askDomain (
						baseQuery.domainInstance.domainActor, 
						baseQuery.query, 
						baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.domainInstance.domainActor, 
						query, 
						baseQuery.domainInstance.timeout
					)
				);
			
			return promises;
		}
		
		public <R> Promise<R> execute (final Function2<A, B, R> callback) {
			return sequence (promises ())
				.map (new F.Function<List<Object>, R> () {
					@Override
					public R apply (final List<Object> list) throws Throwable {
						@SuppressWarnings("unchecked")
						final A a = (A)list.get (0);
						
						@SuppressWarnings("unchecked")
						final B b = (B)list.get (1);
						
						return callback.apply (a, b);
					}
				});
		}
		
		public <R> Promise<R> executeFlat (final Function2<A, B, Promise<R>> callback) {
			return sequence (promises ())
				.flatMap (new F.Function<List<Object>, Promise<R>> () {
					@Override
					public Promise<R> apply (final List<Object> list) throws Throwable {
						@SuppressWarnings("unchecked")
						final A a = (A)list.get (0);
						
						@SuppressWarnings("unchecked")
						final B b = (B)list.get (1);
						
						return callback.apply (a, b);
					}
				});
		}
		
		public <R> Promise<R> execute (final Function2<A, B, R> callback, final Function<Throwable, R> errorCallback) {
			return execute (callback).recover (new F.Function<Throwable, R> () {
					@Override
					public R apply (final Throwable a) throws Throwable {
						return errorCallback.apply (a);
					}
				});
		}
	}
	
	public final static class Query3<A, B, C> implements Query, Queryable {
		
		protected final Query2<A, B> baseQuery;
		protected final DomainQuery<C> query;
		
		public Query3 (final Query2<A, B> baseQuery, final DomainQuery<C> query) {
			this.baseQuery = baseQuery;
			this.query = query;
		}
		
		@Override
		public <T extends Entity> Query4<A, B, C, T> get (final Class<T> cls, final String id) {
			return query (id == null ? null : new GetEntity<> (cls, id));
		}
		
		@Override
		public <T extends Entity> Query4<A, B, C, Page<T>> list (final Class<T> cls) {
			return query (new ListEntity<> (cls, 0));
		}
		
		@Override
		public <T extends Entity> Query4<A, B, C, Page<T>> list (final Class<T> cls, long page) {
			return query (new ListEntity<> (cls, page));
		}
		
		@Override
		public <T extends Identifiable> Query4<A, B, C, Response<?>> put (final T value) {
			return query (new PutEntity<> (value));
		}
		
		@Override
		public <T extends Identifiable> Query4<A, B, C, Response<?>> delete (final Class<? extends T> cls, final String id) {
			return query (new DeleteEntity<> (cls, id));
		}
		
		@Override
		public <T> Query4<A, B, C, T> query (final DomainQuery<T> domainQuery) {
			return new Query4<> (this, domainQuery);
		}
		
		private List<Promise<Object>> promises () {
			final List<Promise<Object>> promises = new ArrayList<> ();
			promises.add (
					askDomain (
						baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.query, 
						baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.query, 
						baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.domainInstance.domainActor, 
						query, 
						baseQuery.baseQuery.domainInstance.timeout
					)
				);

			return promises;
		}
		
		public <R> Promise<R> execute (final Function3<A, B, C, R> callback) {
			return sequence (promises ())
				.map (new F.Function<List<Object>, R> () {
					@Override
					public R apply (final List<Object> list) throws Throwable {
						@SuppressWarnings("unchecked")
						final A a = (A)list.get (0);
						
						@SuppressWarnings("unchecked")
						final B b = (B)list.get (1);
						
						@SuppressWarnings("unchecked")
						final C c = (C)list.get (2);
						
						return callback.apply (a, b, c);
					}
				});
		}
		
		public <R> Promise<R> executeFlat (final Function3<A, B, C, Promise<R>> callback) {
			return sequence (promises ())
					.flatMap (new F.Function<List<Object>, Promise<R>> () {
						@Override
						public Promise<R> apply (final List<Object> list) throws Throwable {
							@SuppressWarnings("unchecked")
							final A a = (A)list.get (0);
							
							@SuppressWarnings("unchecked")
							final B b = (B)list.get (1);
							
							@SuppressWarnings("unchecked")
							final C c = (C)list.get (2);
							
							return callback.apply (a, b, c);
						}
					});
		}
		
		public <R> Promise<R> execute (final Function3<A, B, C, R> callback, final Function<Throwable, R> errorCallback) {
			return execute (callback).recover (new F.Function<Throwable, R> () {
					@Override
					public R apply (final Throwable a) throws Throwable {
						return errorCallback.apply (a);
					}
				});
		}
	}
	
	public final static class Query4<A, B, C, D> implements Query, Queryable {
		protected final Query3<A, B, C> baseQuery;
		protected final DomainQuery<D> query;
		
		public Query4 (final Query3<A, B, C> baseQuery, final DomainQuery<D> query) {
			this.baseQuery = baseQuery;
			this.query = query;
		}
		
		private List<Promise<Object>> promises () {
			final List<Promise<Object>> promises = new ArrayList<> ();
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.baseQuery.query, 
						baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.query, 
						baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.query, 
						baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						query, 
						baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			
			return promises;
		}
		
		public <R> Promise<R> execute (final Function4<A, B, C, D, R> callback) {
			return sequence (promises ())
				.map (new F.Function<List<Object>, R> () {
					@Override
					public R apply (final List<Object> list) throws Throwable {
						@SuppressWarnings("unchecked")
						final A a = (A)list.get (0);
						
						@SuppressWarnings("unchecked")
						final B b = (B)list.get (1);
						
						@SuppressWarnings("unchecked")
						final C c = (C)list.get (2);
						
						@SuppressWarnings("unchecked")
						final D d = (D)list.get (3);
						
						return callback.apply (a, b, c, d);
					}
				});
		}
		
		public <R> Promise<R> executeFlat (final Function4<A, B, C, D, Promise<R>> callback) {
			return sequence (promises ())
					.flatMap (new F.Function<List<Object>, Promise<R>> () {
						@Override
						public Promise<R> apply (final List<Object> list) throws Throwable {
							@SuppressWarnings("unchecked")
							final A a = (A)list.get (0);
							
							@SuppressWarnings("unchecked")
							final B b = (B)list.get (1);
							
							@SuppressWarnings("unchecked")
							final C c = (C)list.get (2);
							
							@SuppressWarnings("unchecked")
							final D d = (D)list.get (3);
							
							return callback.apply (a, b, c, d);
						}
					});
		}
		
		public <R> Promise<R> execute (final Function4<A, B, C, D, R> callback, final Function<Throwable, R> errorCallback) {
			
			return execute (callback).recover (new F.Function<Throwable, R> () {
					@Override
					public R apply (final Throwable a) throws Throwable {
						return errorCallback.apply (a);
					}
				});
		}

		@Override
		public <T extends Entity> Query get (final Class<T> cls, final String id) {
			return query (id == null ? null : new GetEntity<> (cls, id));
		}

		@Override
		public <T extends Entity> Query list (final Class<T> cls) {
			return query (new ListEntity<> (cls, 0));
		}

		@Override
		public <T extends Entity> Query list (final Class<T> cls, final long page) {
			return query (new ListEntity<> (cls, page));
		}

		@Override
		public <T extends Identifiable> Query put (final T value) {
			return query (new PutEntity<> (value));
		}

		@Override
		public <T extends Identifiable> Query delete(Class<? extends T> cls, final String id) {
			return query (new DeleteEntity<> (cls, id));
		}

		@Override
		public <T> Query5<A, B, C, D, T> query (final DomainQuery<T> domainQuery) {
			return new Query5<> (this, domainQuery);
		}
	}
	
	public final static class Query5<A, B, C, D, E> implements Query {
		protected final Query4<A, B, C, D> baseQuery;
		protected final DomainQuery<E> query;
		
		public Query5 (final Query4<A, B, C, D> baseQuery, final DomainQuery<E> query) {
			this.baseQuery = baseQuery;
			this.query = query;
		}
		
		private List<Promise<Object>> promises () {
			final List<Promise<Object>> promises = new ArrayList<> ();
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.baseQuery.baseQuery.query, 
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.baseQuery.query, 
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.baseQuery.query, 
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						baseQuery.query, 
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			promises.add (
					askDomain (
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.domainActor, 
						query, 
						baseQuery.baseQuery.baseQuery.baseQuery.domainInstance.timeout
					)
				);
			
			return promises;
		}
		
		public <R> Promise<R> execute (final Function5<A, B, C, D, E, R> callback) {
			return sequence (promises ())
				.map (new F.Function<List<Object>, R> () {
					@Override
					public R apply (final List<Object> list) throws Throwable {
						@SuppressWarnings("unchecked")
						final A a = (A)list.get (0);
						
						@SuppressWarnings("unchecked")
						final B b = (B)list.get (1);
						
						@SuppressWarnings("unchecked")
						final C c = (C)list.get (2);
						
						@SuppressWarnings("unchecked")
						final D d = (D)list.get (3);
						
						@SuppressWarnings("unchecked")
						final E e = (E)list.get (4);
						
						return callback.apply (a, b, c, d, e);
					}
				});
		}
		
		public <R> Promise<R> executeFlat (final Function5<A, B, C, D, E, Promise<R>> callback) {
			return sequence (promises ())
					.flatMap (new F.Function<List<Object>, Promise<R>> () {
						@Override
						public Promise<R> apply (final List<Object> list) throws Throwable {
							@SuppressWarnings("unchecked")
							final A a = (A)list.get (0);
							
							@SuppressWarnings("unchecked")
							final B b = (B)list.get (1);
							
							@SuppressWarnings("unchecked")
							final C c = (C)list.get (2);
							
							@SuppressWarnings("unchecked")
							final D d = (D)list.get (3);
							
							@SuppressWarnings("unchecked")
							final E e = (E)list.get (4);
							
							return callback.apply (a, b, c, d, e);
						}
					});
		}
	}
	
	public static interface Function<A, R> {
        R apply (A a) throws Throwable;
	}
	public static interface Function2<A, B, R> {
        R apply (A a, B b) throws Throwable;
	}
	public static interface Function3<A, B, C, R> {
        R apply (A a, B b, C c) throws Throwable;
	}
    public static interface Function4<A, B, C, D, R> {
        R apply (A a, B b, C c, D d) throws Throwable;
    }
    public static interface Function5<A, B, C, D, E, R> {
    	R apply (A a, B b, C c, D d, E e) throws Throwable;
    }
    
    private static Promise<Object> askDomain (final ActorSelection actorSelection, final DomainQuery<?> query, final long timeout) {
    	if (query == null) {
    		return Promise.<Object>pure (null);
    	}
    	
    	if (query instanceof Constant<?>) {
    		final Constant<?> constant = (Constant<?>) query;
    		
    		return Promise.<Object>pure (constant.value ());
    	}
    	
		return wrap (
				ask (
					actorSelection, 
					query, 
					timeout
				)
			).map (new F.Function<Object, Object>() {

				@Override
				public Object apply (Object o) throws Throwable {
					if (o instanceof NotFound) {
						return null;
					} else {
						return o;
					}
				}
			}).recover (new F.Function<Throwable, Object> () {
				@Override
				public Object apply (final Throwable e) throws Throwable {
					throw new DomainAccessException (e);
				}
			});
    }
    
    private static Lang getLang (){
        final Lang lang;
        
        if (play.mvc.Http.Context.current.get () != null) {
            lang = play.mvc.Http.Context.current ().lang ();
        } else {
            Locale defaultLocale = Locale.getDefault ();
            lang = new Lang (new play.api.i18n.Lang (defaultLocale.getLanguage (), defaultLocale.getCountry ()));
        }
        
        return lang;
    }
    
    public static String message (final String msgKey) {
    	return Messages.get (getLang (), msgKey);
    }
	
    public static String message (final String msgKey, final Long arg) {
    	return Messages.get (getLang (), msgKey, arg);
    }
	
    public static String message (final String msgKey, final String args) {
    	return Messages.get (getLang (), msgKey, args);
    }
	
    public static String message (final Status status) {
    	return message (status, null);
    }
	
    public static String message (final Status status, final MessageContext context) {
    	return message (getLang (), status, context);
    }
    
    public static String message (final Lang lang, final Status status) {
    	return message (lang, status, null);
    }
    
    public static String message (final Lang lang, final Status status, final MessageContext context) {
    	if (status.type () instanceof Enum<?>) {
    		return messageForEnumValue (lang, (Enum<?>) status.type (), context, null);
    	}
    	
    	return Messages.get (lang, status.type ().getClass ().getCanonicalName (), status.type().statusCategory());
    }
    
    public static String message (final Message message) {
    	return message (message, null);
    }
    
    public static String message (final Message message, final MessageContext context) {
    	return message (getLang (), message, context);
    }
    
    public static String message (final Lang lang, final Message message) {
    	return message (lang, message, null);
    }
    
    public static String message (final Lang lang, final Message message, final MessageContext context) {
    	if (message.type () instanceof Enum<?>) {
    		return messageForEnumValue (lang, (Enum<?>) message.type (), context, message.properties ());
    	}
    	
    	return Messages.get (lang, message.type ().getClass ().getCanonicalName ()/*, message.values()*/);
    }
    
    private static String messageForEnumValue (final Lang lang, final Enum<?> enumValue, final MessageContext context, final MessageProperties properties) {
    	final String key = enumValue.getDeclaringClass ().getCanonicalName () + "." + enumValue.name ();
    	final Object[] args = createMessageParameters (lang, enumValue, properties);
    	
    	return Messages.get (lang, context == null ? key : key + "." + context.name(), args);
    }
    
    private static Object[] createMessageParameters (final Lang lang, final Enum<?> enumValue, final MessageProperties properties) {
    	if (properties == null) {
    		return new Object[0];
    	}
    	
    	final BeanInfo beanInfo;
		try {
			beanInfo = Introspector.getBeanInfo (properties.getClass ());
		} catch (IntrospectionException e) {
			throw new RuntimeException (e);
		}
		
    	final Map<String, PropertyDescriptor> propertyDescriptors = new HashMap<> ();
    	
    	for (final PropertyDescriptor pd: beanInfo.getPropertyDescriptors ()) {
    		if ("entityType".equals (pd.getName ()) || "title".equals (pd.getName ()) || "identification".equals (pd.getName ())) {
    			continue;
    		}
    		if (pd.getReadMethod () == null) {
    			continue;
    		}
    		
    		propertyDescriptors.put (pd.getName (), pd);
    	}

    	// Special cases, zero or one arguments:
    	if (propertyDescriptors.isEmpty ()) {
    		return new Object[0];
    	} else if (propertyDescriptors.size () == 1) {
    		return new Object[] { getValue (lang, properties, propertyDescriptors.values().iterator().next()) };
    	} 
    	
    	// Determine the order of the properties:
    	final String key = enumValue.getDeclaringClass ().getCanonicalName () + "." + enumValue.name () + ".properties";
    	if (!Messages.isDefined (lang, key)) {
    		return new Object[0];
    	}
    	
    	final String[] parts = Messages.get (lang, key).split (",");
    	final ArrayList<Object> args = new ArrayList<> ();
    	
    	for (final String part: parts) {
    		if (propertyDescriptors.containsKey (part.trim ())) {
    			args.add (getValue (lang, properties, propertyDescriptors.get (part.trim ())));
    		}
    	}
    	
    	return args.toArray ();
    }
    
    private static Object getValue (final Lang lang, final MessageProperties properties, final PropertyDescriptor propertyDescriptor) {
    	final Object value;
    	try {
			value = propertyDescriptor.getReadMethod ().invoke (properties);
		} catch (IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			throw new RuntimeException (e);
		}
    	
    	if (value instanceof Enum<?>) {
    		return messageForEnumValue (lang, (Enum<?>)value, null, null);
    	}
    	
    	return value;
    }
    
    public static class Constant<T> implements DomainQuery<T> {
		private static final long serialVersionUID = 1L;
		
		private final T value;
    	
    	public Constant (final T value) {
    		this.value = value;
    	}
    	
    	public T value () {
    		return value;
    	}
    }
    
    public static class GenericMessageProperties {
    	private EntityType entityType;
    	private String title;
    	private String identification;
    	private final Map<String, Object> properties = new HashMap<String, Object> ();
    	
		public EntityType getEntityType() {
			return entityType;
		}
		public void setEntityType(EntityType entityType) {
			this.entityType = entityType;
		}
		public String getTitle() {
			return title;
		}
		public void setTitle(String title) {
			this.title = title;
		}
		public String getIdentification() {
			return identification;
		}
		public void setIdentification(String identification) {
			this.identification = identification;
		}
		
		@JsonAnyGetter
		public Map<String, Object> getProperties() {
			return Collections.unmodifiableMap (properties);
		}
		
		@JsonAnySetter
		public void setProperty (final String name, final Object value) {
			properties.put (name, value);
		}
    }
    
    public static class DomainAccessException extends Exception {
		private static final long serialVersionUID = 5265653848067852534L;
		
		public DomainAccessException (final Throwable cause) {
			super (cause);
		}
    }
}