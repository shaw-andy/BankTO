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

package anthos.samples.bankofanthos.ledgerwriter;

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.lang.Nullable;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BANKTO-2091: durable duplicate-request protection.
 *
 * The existing controller test only proves same-instance sequential
 * rejection via an in-memory cache. These tests require one ledger entry
 * per request identity across replicas, process restart, and races.
 */
class DuplicateRequestProtectionTest {

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String BALANCES_API_ADDR = "balancereader:8080";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String REQUEST_UUID = "incident-request-uuid";
    private static final String OTHER_REQUEST_UUID = "separate-payment-uuid";
    private static final long AWAIT_SECONDS = 5L;

    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private JWTVerifier verifier;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private Clock clock;

    @BeforeEach
    void setUp() {
        initMocks(this);
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerWriterController.JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(REQUEST_UUID);
    }

    @Test
    @DisplayName("Same request identity on two ledgerwriter instances creates one ledger entry")
    void duplicateUuidAcrossInstancesCreatesOneLedgerEntry() {
        RecordingTransactionRepository ledger = new RecordingTransactionRepository();
        LedgerWriterController instanceA = newController(ledger);
        LedgerWriterController instanceB = newController(ledger);

        ResponseEntity<?> first = instanceA.addTransaction(BEARER_TOKEN, transaction);
        ResponseEntity<?> retry = instanceB.addTransaction(BEARER_TOKEN, transaction);

        assertCreated(first);
        assertDuplicateRejected(retry);
        assertEquals(1, ledger.savedCount());
    }

    @Test
    @DisplayName("Same request identity after process restart creates one ledger entry")
    void duplicateUuidAfterRestartCreatesOneLedgerEntry() {
        RecordingTransactionRepository ledger = new RecordingTransactionRepository();
        LedgerWriterController originalProcess = newController(ledger);

        ResponseEntity<?> first = originalProcess.addTransaction(
                BEARER_TOKEN, transaction);
        LedgerWriterController restartedProcess = newController(ledger);
        ResponseEntity<?> retry = restartedProcess.addTransaction(
                BEARER_TOKEN, transaction);

        assertCreated(first);
        assertDuplicateRejected(retry);
        assertEquals(1, ledger.savedCount());
    }

    @Test
    @DisplayName("Concurrent duplicate request identities create one ledger entry")
    void concurrentDuplicateUuidCreatesOneLedgerEntry() throws Exception {
        CyclicBarrier bothReachedSave = new CyclicBarrier(2);
        RecordingTransactionRepository ledger =
                new RecordingTransactionRepository(bothReachedSave);
        LedgerWriterController controller = newController(ledger);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ResponseEntity<?>> first = executor.submit(
                    () -> controller.addTransaction(BEARER_TOKEN, transaction));
            Future<ResponseEntity<?>> second = executor.submit(
                    () -> controller.addTransaction(BEARER_TOKEN, transaction));

            ResponseEntity<?> firstResult = first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            ResponseEntity<?> secondResult = second.get(AWAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals(1, countCreated(firstResult, secondResult));
            assertEquals(1, countDuplicateRejected(firstResult, secondResult));
            assertEquals(1, ledger.savedCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Distinct request identities create independent ledger entries")
    void distinctUuidsCreateIndependentLedgerEntries() {
        RecordingTransactionRepository ledger = new RecordingTransactionRepository();
        LedgerWriterController controller = newController(ledger);
        Transaction otherPayment = mock(Transaction.class);
        when(otherPayment.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(otherPayment.getRequestUuid()).thenReturn(OTHER_REQUEST_UUID);

        ResponseEntity<?> first = controller.addTransaction(
                BEARER_TOKEN, transaction);
        ResponseEntity<?> second = controller.addTransaction(
                BEARER_TOKEN, otherPayment);

        assertCreated(first);
        assertCreated(second);
        assertEquals(2, ledger.savedCount());
    }

    private LedgerWriterController newController(TransactionRepository repository) {
        return new LedgerWriterController(
                verifier,
                disabledMeterRegistry(),
                repository,
                transactionValidator,
                LOCAL_ROUTING_NUM,
                BALANCES_API_ADDR,
                VERSION);
    }

    private StackdriverMeterRegistry disabledMeterRegistry() {
        return new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String projectId() {
                return "test";
            }

            @Override
            @Nullable
            public String get(String key) {
                return null;
            }
        }, clock);
    }

    private static void assertCreated(ResponseEntity<?> response) {
        assertNotNull(response);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    private static void assertDuplicateRejected(ResponseEntity<?> response) {
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION, response.getBody());
    }

    private static int countCreated(ResponseEntity<?>... responses) {
        int count = 0;
        for (ResponseEntity<?> response : responses) {
            if (response.getStatusCode() == HttpStatus.CREATED) {
                count++;
            }
        }
        return count;
    }

    private static int countDuplicateRejected(ResponseEntity<?>... responses) {
        int count = 0;
        for (ResponseEntity<?> response : responses) {
            if (response.getStatusCode() == HttpStatus.BAD_REQUEST
                    && EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION.equals(
                            response.getBody())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Records ledger writes and enforces unique request identity the same
     * way the TRANSACTIONS unique constraint does.
     */
    private static final class RecordingTransactionRepository
            implements TransactionRepository {

        private final List<Transaction> saved =
                Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<String, Transaction> byRequestUuid =
                new ConcurrentHashMap<>();
        private final CyclicBarrier saveBarrier;

        RecordingTransactionRepository() {
            this(null);
        }

        RecordingTransactionRepository(CyclicBarrier saveBarrier) {
            this.saveBarrier = saveBarrier;
        }

        int savedCount() {
            return saved.size();
        }

        @Override
        public boolean existsByRequestUuid(String requestUuid) {
            return requestUuid != null && byRequestUuid.containsKey(requestUuid);
        }

        @Override
        public <S extends Transaction> S save(S entity) {
            awaitSaveBarrier();
            String requestUuid = entity.getRequestUuid();
            if (requestUuid != null && !requestUuid.isEmpty()) {
                Transaction previous = byRequestUuid.putIfAbsent(
                        requestUuid, entity);
                if (previous != null) {
                    throw new DataIntegrityViolationException(
                            "duplicate request uuid");
                }
            }
            saved.add(entity);
            return entity;
        }

        private void awaitSaveBarrier() {
            if (saveBarrier == null) {
                return;
            }
            try {
                saveBarrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("save barrier failed", e);
            }
        }

        @Override
        public <S extends Transaction> Iterable<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<Transaction> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<Transaction> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<Transaction> findAllById(Iterable<Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Transaction entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllById(Iterable<? extends Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(Iterable<? extends Transaction> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll() {
            throw new UnsupportedOperationException();
        }
    }
}
