/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgermonolith;

import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LedgerMonolithControllerTest {

    private LedgerMonolithController ledgerMonolithController;

    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private JWTVerifier verifier;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private LoadingCache<String, AccountInfo> ledgerReaderCache;
    @Mock
    private LedgerReader ledgerReader;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String PUB_KEY_PATH = "unused";

    @BeforeEach
    void setUp() {
        initMocks(this);
        ledgerMonolithController = newLedgerMonolithController();

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerMonolithController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    private LedgerMonolithController newLedgerMonolithController() {
        return new LedgerMonolithController(
                PUB_KEY_PATH,
                ledgerReaderCache,
                verifier,
                transactionRepository,
                transactionValidator,
                ledgerReader,
                LOCAL_ROUTING_NUM,
                VERSION);
    }

    private void stubDurableRequestUuidUniqueness() {
        ConcurrentHashMap<String, Boolean> persisted = new ConcurrentHashMap<>();
        when(transactionRepository.existsByRequestUuid(anyString()))
                .thenAnswer(invocation ->
                        persisted.containsKey(invocation.getArgument(0)));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction toSave = invocation.getArgument(0);
                    String requestUuid = toSave.getRequestUuid();
                    if (requestUuid != null && !requestUuid.isEmpty()) {
                        Boolean previous = persisted.putIfAbsent(
                                requestUuid, Boolean.TRUE);
                        if (previous != null) {
                            throw new DataIntegrityViolationException(
                                    "duplicate key value violates unique "
                                            + "constraint "
                                            + "\"transactions_request_uuid_key\"");
                        }
                    }
                    return toSave;
                });
    }

    @Test
    @DisplayName("When duplicate UUID transactions are sent to one instance, "
            + "second one is rejected with HTTP status 400")
    void addTransactionWhenDuplicateUuidExceptionThrown(TestInfo testInfo) {
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        final ResponseEntity originalResult =
                ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction);
        final ResponseEntity duplicateResult =
                ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction);

        assertNotNull(originalResult);
        assertEquals(HttpStatus.CREATED, originalResult.getStatusCode());
        assertNotNull(duplicateResult);
        assertEquals(
                EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION,
                duplicateResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, duplicateResult.getStatusCode());
    }

    @Test
    @DisplayName("When the same UUID is submitted to different monolith "
            + "instances, the retry is rejected and saved once")
    void addTransactionRejectsDuplicateUuidAcrossControllerInstances(
            TestInfo testInfo) {
        stubDurableRequestUuidUniqueness();
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        LedgerMonolithController instanceA = newLedgerMonolithController();
        LedgerMonolithController instanceB = newLedgerMonolithController();

        final ResponseEntity originalResult =
                instanceA.addTransaction(BEARER_TOKEN, transaction);
        final ResponseEntity duplicateResult =
                instanceB.addTransaction(BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.CREATED, originalResult.getStatusCode());
        assertEquals(
                EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION,
                duplicateResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, duplicateResult.getStatusCode());
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("When duplicate UUID requests arrive concurrently, "
            + "only one ledger entry is created")
    void addTransactionRejectsConcurrentDuplicateUuid() throws Exception {
        stubDurableRequestUuidUniqueness();
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn("concurrent-request-id");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<ResponseEntity> results =
                Collections.synchronizedList(new ArrayList<>());
        Runnable submit = () -> {
            try {
                start.await();
                results.add(ledgerMonolithController.addTransaction(
                        BEARER_TOKEN, transaction));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
        pool.submit(submit);
        pool.submit(submit);
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        long created = results.stream()
                .filter(response -> response.getStatusCode()
                        == HttpStatus.CREATED)
                .count();
        long rejected = results.stream()
                .filter(response -> response.getStatusCode()
                        == HttpStatus.BAD_REQUEST)
                .count();
        assertEquals(1, created);
        assertEquals(1, rejected);
    }

    @Test
    @DisplayName("Distinct request identities are processed independently")
    void addTransactionAllowsDistinctUuidsAcrossControllerInstances() {
        stubDurableRequestUuidUniqueness();
        Transaction otherTransaction = mock(Transaction.class);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn("request-id-a");
        when(otherTransaction.getFromRoutingNum())
                .thenReturn(NON_LOCAL_ROUTING_NUM);
        when(otherTransaction.getRequestUuid()).thenReturn("request-id-b");

        LedgerMonolithController instanceA = newLedgerMonolithController();
        LedgerMonolithController instanceB = newLedgerMonolithController();

        final ResponseEntity firstResult =
                instanceA.addTransaction(BEARER_TOKEN, transaction);
        final ResponseEntity secondResult =
                instanceB.addTransaction(BEARER_TOKEN, otherTransaction);

        assertEquals(HttpStatus.CREATED, firstResult.getStatusCode());
        assertEquals(HttpStatus.CREATED, secondResult.getStatusCode());
        verify(transactionRepository, times(1)).save(transaction);
        verify(transactionRepository, times(1)).save(otherTransaction);
    }
}
