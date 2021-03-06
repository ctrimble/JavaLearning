import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.inject.matcher.Matchers;
import org.junit.Test;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

public class MethodInterceptorTest {
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public static @interface Count {};

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public static @interface Timed {};

  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public static @interface Tracked {};


  @Tracked
  public static class ExampleMethods {
    @Count
    @Timed
    public int intMethod() { randomSleep(); return 1; }

    @Count
    @Timed
    public void voidMethod() { randomSleep(); }

    public void unannotatedMethod() { randomSleep(); }
  }

  @Test
  public void simpleInterceptor() throws NoSuchMethodException, SecurityException {
    Registry registry = new Registry();
    Injector injector = Guice.createInjector(new AbstractModule() {

      @Override
      protected void configure() {
        bindInterceptor(Matchers.annotatedWith(Tracked.class),Matchers.annotatedWith(Count.class), i -> {
          return registry.counter(i.getMethod()).count(i::proceed);
        });
      }
    });
    
    Method intMethod = ExampleMethods.class.getMethod("intMethod");
    Method voidMethod = ExampleMethods.class.getMethod("voidMethod");

    ExampleMethods m = injector.getInstance(ExampleMethods.class);
    
    assertThat(registry.getCounter(intMethod).isPresent(), equalTo(false));
    assertThat(registry.getCounter(voidMethod).isPresent(), equalTo(false));
    
    m.intMethod();
    m.voidMethod();
    
    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(1));
    
    m.voidMethod();

    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(2));
  }
  
  @Test
  public void multipleInterceptors() throws NoSuchMethodException, SecurityException {
    Registry registry = new Registry();
    Injector injector = Guice.createInjector(new AbstractModule() {
      @Override public void configure() {
        bindInterceptor(Matchers.annotatedWith(Tracked.class),Matchers.annotatedWith(Count.class), i -> {
          return registry.counter(i.getMethod()).count(i::proceed);
        });

        bindInterceptor(Matchers.annotatedWith(Tracked.class),Matchers.annotatedWith(Timed.class), i -> {
          return registry.timer(i.getMethod()).time(i::proceed);
        });
      }
    });
    
    Method intMethod = ExampleMethods.class.getMethod("intMethod");
    Method voidMethod = ExampleMethods.class.getMethod("voidMethod");
    
    ExampleMethods m = injector.getInstance(ExampleMethods.class);
    
    assertThat(registry.getCounter(intMethod).isPresent(), equalTo(false));
    assertThat(registry.getTimer(voidMethod).isPresent(), equalTo(false));
    
    m.intMethod();
    m.voidMethod();

    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(1));
    
    m.voidMethod();

    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(2));

    assertThat(registry.getTimer(intMethod).get().getValue().getAsDouble(), greaterThan(0.0d));
    assertThat(registry.getTimer(voidMethod).get().getValue().getAsDouble(), greaterThan(0.0d));
  }

  @Test
  public void addingAnnotations() throws NoSuchMethodException, SecurityException {
    Registry registry = new Registry();
    Injector injector = Guice.createInjector(new AbstractModule() {
      @Override public void configure() {
        bindInterceptor(Matchers.annotatedWith(Tracked.class),Matchers.any(), i -> {
          return registry.counter(i.getMethod()).count(i::proceed);
        }, i -> {
          return registry.timer(i.getMethod()).time(i::proceed);
        });
      }
    });

    Method intMethod = ExampleMethods.class.getMethod("intMethod");
    Method voidMethod = ExampleMethods.class.getMethod("voidMethod");
    Method unannotatedMethod = ExampleMethods.class.getMethod("unannotatedMethod");

    ExampleMethods m = injector.getInstance(ExampleMethods.class);

    assertThat(registry.getCounter(intMethod).isPresent(), equalTo(false));
    assertThat(registry.getTimer(voidMethod).isPresent(), equalTo(false));

    m.intMethod();
    m.voidMethod();
    m.unannotatedMethod();

    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(unannotatedMethod).get().getValue(), equalTo(1));

    m.voidMethod();

    assertThat(registry.getCounter(intMethod).get().getValue(), equalTo(1));
    assertThat(registry.getCounter(voidMethod).get().getValue(), equalTo(2));

    assertThat(registry.getTimer(intMethod).get().getValue().getAsDouble(), greaterThan(0.0d));
    assertThat(registry.getTimer(voidMethod).get().getValue().getAsDouble(), greaterThan(0.0d));
  }
  
  public static interface ThrowingCallable<T> {
    public T call() throws Throwable;
  }
  
  public static class Counter {
    AtomicInteger count = new AtomicInteger();
    
    public <T> T count( ThrowingCallable<T> c ) throws Throwable {
      count.incrementAndGet();
      return c.call();
    }
    
    public int getValue() {
      return count.get();
    }
  }
  
  public static class Timer {
    public EvictingQueue<Long> times = EvictingQueue.create(10);
    
    public <T> T time( ThrowingCallable<T> c ) throws Throwable {
      long startTime = System.currentTimeMillis();
      try {
        return c.call();
      } finally {
        long endTime = System.currentTimeMillis();
        times.add(endTime-startTime);
      }
    }
    
    public OptionalDouble getValue() {
      return times.stream().mapToLong(Long::longValue).average();
    }
  }
  
  public static class Registry {
    private Map<Method, Counter> counters = Maps.newConcurrentMap();
    private Map<Method, Timer> timers = Maps.newConcurrentMap();
    
    public Counter counter(Method method) {
      return counters.computeIfAbsent(method, k->new Counter());
    }
    
    public Timer timer( Method method ) {
      return timers.computeIfAbsent(method, k->new Timer());
    }
    
    public Optional<Counter> getCounter(Method method) {
      return Optional.ofNullable(counters.get(method));
    }
    
    public Optional<Timer> getTimer(Method method) {
      return Optional.ofNullable(timers.get(method));
    }
  }
    
  public static Random random = new Random();
  public static void randomSleep() {
    Throwing.runnable(()->MILLISECONDS.sleep(random.nextInt(9)+1)).run();
  }

}
