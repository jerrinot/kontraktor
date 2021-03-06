package kontraktor;

import de.ruedigermoeller.kontraktor.*;
import de.ruedigermoeller.kontraktor.Future;
import de.ruedigermoeller.kontraktor.annotations.*;
import de.ruedigermoeller.kontraktor.Promise;
import kontraktor.BasicTest.ServiceActor.*;
import org.junit.Test;

import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static de.ruedigermoeller.kontraktor.Actors.*;
import static org.junit.Assert.assertTrue;

/**
 * Created by ruedi on 06.05.14.
 */
public class BasicTest {

    public static class Bench extends Actor<Bench> {
        protected int count;
        public void benchCall( String a, String b, String c) {
            count++;
        }
    }

    private long bench(Bench actorA) {
        long tim = System.currentTimeMillis();
        int numCalls = 1000 * 1000 * 10;
        for ( int i = 0; i < numCalls; i++ ) {
            actorA.benchCall("A", "B", "C");
        }
        final long l = (numCalls / (System.currentTimeMillis() - tim)) * 1000;
        System.out.println("tim "+ l +" calls per sec");
        actorA.getDispatcher().waitEmpty(3000*1000);
        return l;
    }

    @Test
    public void callBench() {
        Bench b = SpawnActor(Bench.class);
        bench(b);
        long callsPerSec = bench(b);
        b.stop();
        assertTrue(callsPerSec > 2 * 1000 * 1000);
    }

    public static class BenchSub extends Bench {
        @Override
        public void benchCall(String a, String b, String c) {
            super.benchCall(a, b, c);
        }
          
        public void getResult( Callback<Integer> cb ) {
            cb.receiveResult(count,null);
        }
    }

    @Test
    public void testInheritance() {
        final BenchSub bs = SpawnActor(BenchSub.class);
        for (int i : new int[10] ) {
            bs.benchCall("u", "o", null);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        bs.getResult( new Callback<Integer>() {
            @Override
            public void receiveResult(Integer result, Object error) {
                assertTrue(result.intValue()==10);
                bs.stop();
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static interface SomeCallbackHandler {
        public void callbackReceived( Object callback );
    }

    public static class ServiceActor extends Actor<ServiceActor> {

        public static interface DataAccess {
            HashMap getMap();
        }

        HashMap myPrivateData;

        public void init() {
            myPrivateData = new HashMap();
            myPrivateData.put("One", "Two");
            myPrivateData.put(3, 4);
            myPrivateData.put("five", 6);
        }

        public void getString( SomeCallbackHandler callback ) {
            callback.callbackReceived("Hallo");
        }

        public Future<String> concat(String other) {
            return new Promise<>("Hallo"+other);
        }

        public void getStringAnnotated( @InThread SomeCallbackHandler callback ) {
            callback.callbackReceived("Hallo");
        }

        @Override
        protected Object getActorAccess() {
            return new DataAccess() {
                @Override
                public HashMap getMap() {
                    return myPrivateData;
                }
            };
        }

    }

    public static class MyActor extends Actor<MyActor> {

        ServiceActor service;
        volatile int success = 0;

        public void init(ServiceActor service) {
            this.service = service;
        }


        public void callbackTest() {

            final Thread callerThread = Thread.currentThread();
            service.getString(InThread(new SomeCallbackHandler() {
                @Override
                public void callbackReceived(Object callback) {
                    if (callerThread != Thread.currentThread()) {
                        throw new RuntimeException("Dammit");
                    } else {
                        success++;
                        System.out.println("Alles prima");
                    }
                }
            }));
            service.getStringAnnotated(new SomeCallbackHandler() {
                @Override
                public void callbackReceived(Object callback) {
                    if (callerThread != Thread.currentThread()) {
                        throw new RuntimeException("Dammit 1");
                    } else {
                        success++;
                        System.out.println("Alles prima 1");
                    }
                }
            });

            service.executeInActorThread(
                    new ActorRunnable() {
                        @Override
                        public void run(Object actorAccess, Actor actorImpl, Callback resultReceiver) {
                            if ( service.getDispatcher() == Thread.currentThread() ) {
                                success++;
                            } else {
                                System.out.println("POKPOK err");
                            }
                            DataAccess access = (DataAccess) actorAccess;
                            Iterator iterator = access.getMap().keySet().iterator();
                            while( iterator.hasNext() ) {
                                Object o = iterator.next();
                                if ( "five".equals(o) ) {
                                    resultReceiver.receiveResult(access.getMap().get(o),null);
                                }
                            }
                        }
                    },
                    new Callback() {
                        @Override
                        public void receiveResult(Object result, Object error) {
                            if (callerThread != Thread.currentThread()) {
                                throw new RuntimeException("Dammit");
                            } else {
                                success++;
                                System.out.println("Alles prima 2");
                            }
                            System.out.println("res "+result);
                        }
                    }
            );

        }
    }

    @Test
    public void inThreadTest() throws InterruptedException {
        ServiceActor service = AsActor(ServiceActor.class);
        service.init();

        MyActor cbActor = AsActor(MyActor.class);
        cbActor.init(service);
        cbActor.callbackTest();

        Thread.sleep(1000);

        cbActor.stop();
        assertTrue(((MyActor)cbActor.getActor()).success == 4);
        service.stop();

    }

    public static class Overload extends Actor {

        public void a(int x, Callback<Integer> cb) {
            cb.receiveResult(x,null);
        }

        public void a(int x, int y, Callback<Integer> cb) {
            cb.receiveResult(y,null);
        }

    }


    @Test
    public void testOverload() {
        try {
            // verify exception is thrown
            Overload ov = AsActor(Overload.class);
            assertTrue(false);
        } catch (Exception e) {
            // expected, cannot overload with argument types, number (too expensive)
            //e.printStackTrace();
        }
    }


    public static class SleepActor extends Actor<SleepActor> {

        private String name;

        public Future init(String na) {
            name = na;
            return new Promise(na);
        }

        public Future<String> getName() {
            return new Promise<>(name);
        }

        public Future<Long> sleep() {
            long millis = (long) (Math.random() * 1000);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return new Promise<>(millis);
        }

        public Future<String> say( String s ) {
            System.out.println(name+" says '"+s+"'");
            return new Promise<>("result "+s);
        }

    }

    public static class SleepCallerActor extends Actor<SleepCallerActor> {
        SleepActor act[];
        Future<Long> results[];

        public void test() {
            act = new SleepActor[10];
            results = new Future[act.length];
            for (int i = 0; i < act.length; i++) {
                act[i] = Actors.SpawnActor(SleepActor.class);
                act[i].init("("+i+")");
            }

            for (int i = 0; i < act.length; i++) {
                results[i] = act[i].sleep();
            }

            yield(results).then(new Callback<Future[]>() {
                @Override
                public void receiveResult(Future[] result, Object error) {
                    System.out.println("now "+System.currentTimeMillis());
                    for (int i = 0; i < result.length; i++) {
                        Future future = result[i];
                        System.out.println("sleep "+i+" "+future.getResult());
                    }
                }
            });

        }

        public void stop() {
            for (int i = 0; i < act.length; i++) {
                act[i].stop();
            }
             super.stop();
        }


    }


    @Test
    public void testYield() {
        SleepCallerActor act = Actors.SpawnActor(SleepCallerActor.class);
        System.out.println("now "+System.currentTimeMillis());
        act.test();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        act.stop();
    }

    public static class TestBlockingAPI extends Actor<TestBlockingAPI> {

        public void get( final String url, final Callback<String> content ) {
            final Thread myThread = getDispatcher();
            Actors.Execute(
                new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return new Scanner( new URL(url).openStream(), "UTF-8" ).useDelimiter("\\A").next();
                    }
                }).then(
                new Callback<String>() {
                    @Override
                    public void receiveResult(String result, Object error) {
                        if ( Thread.currentThread() == myThread ) {
                            content.receiveResult(result,null);
                        } else {
                            content.receiveResult(null, "wrong thread");
                        }
                    }
            });
        }
    }

    public static class FutureTest extends Actor<FutureTest> {

        public Future<String> getString( String s ) {
            return new Promise<>(s+"_String");
        }

    }

    public static class FutureTestCaller extends Actor<FutureTestCaller> {

        FutureTest ft;

        public void init() {
            ft = Actors.SpawnActor(FutureTest.class);
        }

        public Future<String> doTestCall() {
            final Promise<String> stringResult = new Promise<String>().setId("doTestCall");
            ft.getString("13")
                .then(new Callback<String>() {
                    @Override
                    public void receiveResult(String result, Object error) {
                        stringResult.receiveResult(result, null);
                    }
                });
            return stringResult;
        }

        public void doTestCall1(final Future<String> stringResult) {
            ft.getString("13")
                    .then(new Callback<String>() {
                        @Override
                        public void receiveResult(String result, Object error) {
                            stringResult.receiveResult(result, null);
                        }
                    });
        }
    }

    @Test
    public void testFuture() {
        FutureTest ft = Actors.SpawnActor(FutureTest.class);
        final AtomicReference<String> outerresult0 = new AtomicReference<>();
        ft.getString("oj").then(new Callback<String>() {
            @Override
            public void receiveResult(String result, Object error) {
                System.out.println("simple:" + result);
                outerresult0.set(result);
            }
        });

        FutureTestCaller test = Actors.SpawnActor(FutureTestCaller.class);
        test.init();

        final AtomicReference<String> outerresult = new AtomicReference<>();
        test.doTestCall()
            .then(new Callback<String>() {
                @Override
                public void receiveResult(String result, Object error) {
                    System.out.println("outer result " + result);
                    outerresult.set(result);
                }
            });

        final AtomicReference<String> outerresult1 = new AtomicReference<>();
        Future<String> f = new Promise<>();
        test.doTestCall1(f);
        f.then(new Callback<String>() {
            @Override
            public void receiveResult(String result, Object error) {
                System.out.println("outer1 result:"+result);
                outerresult1.set(result);
            }
        });

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        assertTrue(outerresult0.get().equals("oj_String"));
        assertTrue(outerresult.get().equals("13_String"));
        assertTrue(outerresult1.get().equals("13_String"));
    }


    public static class DelayedTest extends Actor<DelayedTest> {

        public void delay(long started) {
            delay_threads.set(getDispatcher() == Thread.currentThread());
            if (!delay_threads.get()) {
                System.out.println("current thread " + Thread.currentThread().getName());
                System.out.println("dispatcher " + getDispatcher().getName());
            }
            System.out.println("ThreadsCheck:" + delay_threads.get());
            long l = System.currentTimeMillis() - started;
            System.out.println("DELAY:" + l);
            delay_time.set(l);
        }
    }

    final static AtomicBoolean delay_threads = new AtomicBoolean(false);
    final static AtomicLong delay_time = new AtomicLong(0);

    public static class DelayedCaller extends Actor {

        public void delay() {
            final DelayedTest test = Actors.SpawnActor(DelayedTest.class);
            final long now = System.currentTimeMillis();
            Actors.Delayed(100,new Runnable() {
                @Override
                public void run() {
                    test.delay(now);
                }
            });
        }
    }

    @Test
    public void testDelayed() {
        DelayedCaller caller = Actors.SpawnActor(DelayedCaller.class);
        caller.delay();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertTrue(delay_threads.get());
        assertTrue(delay_time.get() >= 100 && delay_time.get() < 120);
    }

    @Test
    public void testBlockingCall() {
        final AtomicInteger success = new AtomicInteger(0);
        TestBlockingAPI actor = AsActor(TestBlockingAPI.class);
        actor.get("http://www.google.com", new Callback<String>() {
            @Override
            public void receiveResult(String result, Object error) {
                if ( error != null )
                    success.set(1);
                else
                    success.set(2);
            }
        });
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertTrue(success.get()!=1); // if no response (proxy etc) also return true
    }


    public static class FutureSequenceActor extends Actor<FutureSequenceActor> {

        public Future run() {

            final Promise mresult = new Promise();

            final SleepActor sleepers[] = new SleepActor[4];
            for (int i = 0; i < sleepers.length; i++) {
                sleepers[i] = SpawnActor(SleepActor.class);
            }



            final Future<Future[]> finished = new Promise<>();

            msg($$(SleepActor.class).init("saved message"))
            .yield(sleepers)
            .then(new Callback<Future[]>() {
                @Override
                public void receiveResult(Future[] result, Object error) {
                    System.out.println("yield done");
                }
            }).then(new Callback() {
                @Override
                public void receiveResult(Object result, Object error) {
                    System.out.println("start getting names");
//                    yield( sleepers[0].getName(),
//                           sleepers[1].getName(),
//                           sleepers[2].getName(),
//                           sleepers[3].getName()
//                         ).then(finished);
                    // same:
                    msg( $$(SleepActor.class).getName() ).yield( sleepers ).then( finished );
                }
            });

            finished.then(new Callback<Future[]>() {
                @Override
                public void receiveResult(Future[] result, Object error) {
                    System.out.println("finished, checking results");
                    for (int i = 0; i < result.length; i++) {
                        Future future = result[i];
                        assertTrue("saved message".equals(future.getResult()));
                    }
                }
            }).then(new Callback() {
                @Override
                public void receiveResult(Object result, Object error) {
                    mresult.receiveResult("void",null);
                    for (int i = 0; i < sleepers.length; i++) {
                        SleepActor sleeper = sleepers[i];
                        sleeper.stop();
                    }
                    System.out.println("--stopped--");
                }
            });
            return mresult;
        }

        public Future<String> testFunc(String s) {
            return new Promise(s+s);
        }
    }


    @Test
    public void futureSequenceTest() throws InterruptedException {
        FutureSequenceActor fut = SpawnActor(FutureSequenceActor.class);
        final CountDownLatch latch = new CountDownLatch(1);

        fut.testFunc("A").then(new Callback<String>() {
            @Override
            public void receiveResult(String result, Object error) {
                System.out.println("received:"+result);
            }
        }).then(new Callback() {
            @Override
            public void receiveResult(Object result, Object error) {
                System.out.println("received 1:"+result);
            }
        }).filter(new Filter() {
            @Override
            public Future filter(final Object result, Object error) {
                System.out.println("filter a in " + result);
                Promise res = new Promise();
                Execute( new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return result + "_fa";
                    }
                }).then(res);
                return res;
            }
        }).filter(new Filter() {
            @Override
            public Future filter(Object result, Object error) {
                System.out.println("filter b in " + result);
                return new Promise(result + "_fb");
            }
        }).then(new Callback() {
            @Override
            public void receiveResult(Object result, Object error) {
                System.out.println("finally "+result);
            }
        });

        fut.run().then(new Callback() {
            @Override
            public void receiveResult(Object result, Object error) {
                latch.countDown();
            }
        });
        latch.await();
    }

    @Test
    public void lockStratTest() {
//        Executor ex = Executors.newCachedThreadPool();
//        for ( int iii : new int[3] ) {
//            ex.execute( new Runnable() {
//                @Override
//                public void run() {
//                    BackOffStrategy backOffStrategy = new BackOffStrategy();
//                    for (int i = 0; i < 1000; i++) {
//                        for (int ii = 0; ii < 160000; ii++) {
//                            backOffStrategy.yield(ii);
//                        }
//                        System.out.println("plop");
//                    }
//                }
//            });
//        }
//        try {
//            Thread.sleep(60000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
    }


}
