package local.tv.streamtest;

import okhttp3.Dns;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CatalogDnsCheck {
    private static final String HOST = "ww8.123moviesfree.net";
    private static final String JSON = "{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\"ww8.123moviesfree.net.\",\"type\":1}],"
            + "\"Answer\":[{\"name\":\"ww8.123moviesfree.net\",\"type\":5,\"TTL\":60,\"data\":\"cdn.example.\"},"
            + "{\"name\":\"cdn.example.\",\"type\":1,\"TTL\":120,\"data\":\"203.0.113.7\"},"
            + "{\"name\":\"unrelated.example.\",\"type\":1,\"TTL\":1,\"data\":\"192.0.2.9\"}]}";
    private static final Dns BROKEN = name -> { throw new UnknownHostException("system lookup failed"); };
    private static void check(boolean result, String failure) { if (!result) throw new AssertionError(failure); }
    private static void reject(String json) throws Exception {
        try { CatalogDns.parse(HOST, json); throw new AssertionError("Accepted invalid DNS response"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--live")) {
            for (String path : new String[]{"/genre/horror/", "/tv-series/"}) {
                try {
                    CatalogParser.Page page = CatalogParser.listing(CatalogHttp.text(CatalogParser.ORIGIN + path, 3145728));
                    check(!page.movies.isEmpty(), "No titles in live catalog");
                    if (path.startsWith("/tv-series/")) check(page.movies.stream().anyMatch(Movie::series), "No seasons in live page");
                    System.out.println("LIVE " + path + ": " + page.movies.size() + " titles");
                    byte[] poster = CatalogHttp.bytes(page.movies.get(0).poster, 4194304);
                    check(poster.length > 100, "Empty live poster");
                    System.out.println("LIVE poster: " + poster.length + " bytes");
                } catch (Exception error) { System.out.println("LIVE " + path + ": " + CatalogHttp.problem(error)); }
            }
            return;
        }
        CatalogDns.Answer answer = CatalogDns.parse(HOST, JSON);
        check(answer.addresses.size() == 1 && answer.addresses.get(0).getHostAddress().equals("203.0.113.7"), "Wrong address chain");
        check(answer.ttlSeconds == 60, "CNAME TTL ignored");
        reject(JSON.replace("\"Status\":0", "\"Status\":3"));
        reject(JSON.replace("\"TC\":false", "\"TC\":true"));
        reject(JSON.replace("ww8.123moviesfree.net.", "wrong.example."));
        reject(JSON.replace("203.0.113.7", "10.0.0.1"));
        reject(JSON.replace("203.0.113.7", "127.0.0.1"));
        reject(JSON.replace("203.0.113.7", "999.0.0.1"));
        reject(JSON.replace("203.0.113.7", "other-host.example"));
        reject(JSON.replace("\"data\":\"cdn.example.\"", "\"data\":\"missing.example.\""));
        AtomicInteger calls = new AtomicInteger(); AtomicLong clock = new AtomicLong();
        CatalogDns.Lookup secure = name -> { calls.incrementAndGet(); return answer; };
        // Oct 21 TV: system DNS succeeded with a server presenting another domain's certificate.
        List<InetAddress> hijacked = Arrays.asList(InetAddress.getByAddress(HOST, new byte[]{(byte) 198, 51, 100, 1}));
        CatalogDns wrongSystem = new CatalogDns(name -> hijacked, secure, clock::get);
        check(wrongSystem.lookup(HOST).equals(answer.addresses), "Trusted a successful wrong system DNS answer");
        check(calls.get() == 1, "Skipped HTTPS DNS for catalog host");
        check(wrongSystem.lookup("www.omdbapi.com").equals(hijacked), "Changed DNS for an unrelated host");
        check(calls.get() == 1, "Sent unrelated hostname to HTTPS DNS");
        calls.set(0); clock.set(0);
        CatalogDns fallback = new CatalogDns(BROKEN, secure, clock::get);
        fallback.lookup(HOST); fallback.lookup(HOST);
        check(calls.get() == 1, "Repeated DNS request within TTL");
        clock.set(TimeUnit.SECONDS.toNanos(61)); fallback.lookup(HOST);
        check(calls.get() == 2, "Retained stale DNS addresses");
        AtomicInteger downCalls = new AtomicInteger();
        CatalogDns secureDown = new CatalogDns(name -> answer.addresses, name -> { downCalls.incrementAndGet(); throw new IOException("down"); }, clock::get);
        check(secureDown.lookup(HOST).equals(answer.addresses), "HTTPS DNS outage blocked system DNS fallback");
        check(secureDown.lookup(HOST).equals(answer.addresses), "Backoff blocked system DNS fallback");
        check(downCalls.get() == 1, "Retried failed HTTPS DNS during backoff");
        for (String host : Arrays.asList("localhost", "www.omdbapi.com", "api.typesafe.ai", "unrelated.example")) {
            try { fallback.lookup(host); throw new AssertionError("Expanded DNS fallback scope"); }
            catch (UnknownHostException expected) { check(!(expected instanceof CatalogDns.FallbackFailure), "Tried fallback for unrelated host"); }
        }
        check(calls.get() == 2, "Disclosed an unrelated hostname");
        AtomicInteger failures = new AtomicInteger();
        CatalogDns broken = new CatalogDns(BROKEN, name -> { failures.incrementAndGet(); throw new IOException("secret URL"); }, clock::get);
        for (int i = 0; i < 3; i++) {
            try { broken.lookup(HOST); throw new AssertionError("Accepted failed DNS fallback"); }
            catch (CatalogDns.FallbackFailure expected) {
                check(CatalogHttp.problem(expected).equals("Catalog DNS-over-HTTPS fallback unavailable"), "Lost fallback diagnostic");
            }
        }
        check(failures.get() == 1, "Poster retries overwhelmed failed resolver");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(16));
        try { broken.lookup(HOST); } catch (CatalogDns.FallbackFailure expected) { }
        check(failures.get() == 2, "Fallback never recovers after failure");
        AtomicInteger interruptedCalls = new AtomicInteger(), interruptedSystem = new AtomicInteger();
        CatalogDns interrupted = new CatalogDns(name -> { interruptedSystem.incrementAndGet(); throw new UnknownHostException("system"); }, name -> {
            if (interruptedCalls.incrementAndGet() == 1) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return answer;
        }, clock::get);
        try {
            try { interrupted.lookup(HOST); throw new AssertionError("Ignored cancellation"); }
            catch (CatalogDns.LookupCancelled expected) {
                check(Thread.currentThread().isInterrupted(), "Lost interrupt status");
                check(CatalogHttp.problem(expected).equals("Catalog request canceled"), "Reported cancellation as resolver failure");
                check(interruptedSystem.get() == 0, "Canceled lookup fell through to system DNS");
            }
        } finally { Thread.interrupted(); }
        check(interrupted.lookup(HOST).equals(answer.addresses), "Cancellation poisoned the shared DNS cache");
        check(interruptedCalls.get() == 2, "Fresh request did not retry after cancellation");
        AtomicInteger preInterruptedCalls = new AtomicInteger();
        CatalogDns preInterrupted = new CatalogDns(name -> { preInterruptedCalls.incrementAndGet(); return answer.addresses; }, secure, clock::get);
        Thread.currentThread().interrupt();
        try {
            try { preInterrupted.lookup(HOST); throw new AssertionError("Started DNS on a canceled worker"); }
            catch (CatalogDns.LookupCancelled expected) { check(preInterruptedCalls.get() == 0, "Canceled worker performed network lookup"); }
        } finally { Thread.interrupted(); }
        AtomicInteger waitingCalls = new AtomicInteger();
        CatalogDns waiting = new CatalogDns(BROKEN, name -> {
            if (waitingCalls.incrementAndGet() == 1) throw new InterruptedException();
            return answer;
        }, clock::get);
        try {
            try { waiting.lookup(HOST); throw new AssertionError("Ignored InterruptedException"); }
            catch (CatalogDns.LookupCancelled expected) { check(Thread.currentThread().isInterrupted(), "Did not restore interrupted status"); }
        } finally { Thread.interrupted(); }
        check(waiting.lookup(HOST).equals(answer.addresses), "Interrupted wait poisoned shared DNS cache");
        AtomicInteger timeoutCalls = new AtomicInteger();
        CatalogDns timeout = new CatalogDns(BROKEN, name -> { timeoutCalls.incrementAndGet(); throw new SocketTimeoutException("timeout"); }, clock::get);
        for (int i = 0; i < 2; i++) {
            try { timeout.lookup(HOST); throw new AssertionError("Ignored DNS timeout"); }
            catch (CatalogDns.FallbackFailure expected) { check(!Thread.currentThread().isInterrupted(), "Mistook timeout for thread cancellation"); }
        }
        check(timeoutCalls.get() == 1, "Timeout no longer backs off");
        AtomicInteger concurrentCalls = new AtomicInteger();
        CatalogDns concurrent = new CatalogDns(BROKEN, name -> { concurrentCalls.incrementAndGet(); return answer; }, clock::get);
        ExecutorService pool = Executors.newFixedThreadPool(4); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<InetAddress>>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(() -> { start.await(); return concurrent.lookup(HOST); }));
            start.countDown();
            for (Future<List<InetAddress>> result : results) check(result.get(2, TimeUnit.SECONDS).equals(answer.addresses), "Concurrent lookup failed");
            check(concurrentCalls.get() == 1, "Simultaneous posters duplicated DNS requests");
        } finally { pool.shutdownNow(); }
        System.out.println("PASS: HTTPS-first catalog DNS, wrong-system-answer regression, system fallback, DNS scope, CNAME/TTL validation, failure recovery, cancellation isolation, timeout backoff and concurrent requests");
    }
}
