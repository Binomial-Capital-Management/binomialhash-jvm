package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.context.BinomialHashContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Multi-tenant isolation using ThreadLocal (Java equivalent of contextvars).
 * User A ingests market data; User B ingests gene data. Each thread sees only its own data.
 */
public class Example09_MultiTenantIsolation {

    public static void main(String[] args) throws InterruptedException {
        BinomialHashContext.setBhFactory(BinomialHash::new);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<String> userAKeys = new AtomicReference<>();
        AtomicReference<String> userBKeys = new AtomicReference<>();
        AtomicReference<Boolean> userASeesGene = new AtomicReference<>();
        AtomicReference<Boolean> userBSeesMarket = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                BinomialHash bh = (BinomialHash) BinomialHashContext.initBinomialHash();
                bh.ingest(toJson(generateMarketData()), "market_data");
                List<Map<String, Object>> keys = bh.keys();
                userAKeys.set(keys.isEmpty() ? "[]" : toJson(keys));
                userASeesGene.set(keys.stream().anyMatch(k -> "gene_data".equals(k.get("label"))));
            } catch (Exception e) {
                userASeesGene.set(false);
            } finally {
                BinomialHashContext.clear();
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                BinomialHash bh = (BinomialHash) BinomialHashContext.initBinomialHash();
                bh.ingest(toJson(generateGeneData()), "gene_data");
                List<Map<String, Object>> keys = bh.keys();
                userBKeys.set(keys.isEmpty() ? "[]" : toJson(keys));
                userBSeesMarket.set(keys.stream().anyMatch(k -> "market_data".equals(k.get("label"))));
            } catch (Exception e) {
                userBSeesMarket.set(false);
            } finally {
                BinomialHashContext.clear();
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();

        printSeparator("User A (market data)");
        System.out.println("Keys: " + userAKeys.get());
        System.out.println("Sees gene_data (should be false): " + userASeesGene.get());

        printSeparator("User B (gene data)");
        System.out.println("Keys: " + userBKeys.get());
        System.out.println("Sees market_data (should be false): " + userBSeesMarket.get());

        printSeparator("Isolation verification");
        boolean isolated = Boolean.FALSE.equals(userASeesGene.get()) && Boolean.FALSE.equals(userBSeesMarket.get());
        System.out.println("Isolation verified: " + isolated);

        executor.shutdown();
    }
}
