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

import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_MANUAL_REVIEW_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LedgerMonolithControllerTest {

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
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String TO_ACCOUNT_NUM = "5678901234";
    private static final String TO_ROUTING_NUM = "567891234";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final int BELOW_HIGH_VALUE_AMOUNT = 999_999;
    private static final int HIGH_VALUE_THRESHOLD_CENTS = 1_000_000;
    private static final int ABOVE_HIGH_VALUE_AMOUNT = 1_000_001;
    private static final long HIGH_VALUE_SENDER_BALANCE = 2_000_000L;

    @BeforeEach
    void setUp() {
        initMocks(this);
        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerMonolithController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given outbound amount is one cent below CAD $10,000, "
            + "transaction is persisted")
    void addTransactionSuccessWhenOutboundAmountBelowHighValue(
            TestInfo testInfo) {
        LedgerMonolithController spyController =
                spy(controllerWithRealValidator());
        stubValidOutboundTransaction(BELOW_HIGH_VALUE_AMOUNT, testInfo);
        doReturn(HIGH_VALUE_SENDER_BALANCE).when(spyController)
                .getAvailableBalance(AUTHED_ACCOUNT_NUM);

        final ResponseEntity actualResult =
                spyController.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transactionRepository).save(transaction);
    }

    @Test
    @DisplayName("Given outbound amount is exactly CAD $10,000, "
            + "return MANUAL_REVIEW_REQUIRED and do not persist")
    void addTransactionFailWhenOutboundAmountEqualsHighValue(
            TestInfo testInfo) {
        assertHighValueOutboundNotPersisted(
                HIGH_VALUE_THRESHOLD_CENTS, testInfo);
    }

    @Test
    @DisplayName("Given outbound amount is above CAD $10,000, "
            + "return MANUAL_REVIEW_REQUIRED and do not persist")
    void addTransactionFailWhenOutboundAmountAboveHighValue(
            TestInfo testInfo) {
        assertHighValueOutboundNotPersisted(
                ABOVE_HIGH_VALUE_AMOUNT, testInfo);
    }

    @Test
    @DisplayName("Given inbound deposit amount is CAD $10,000, "
            + "transaction is persisted")
    void addTransactionSuccessWhenInboundAmountEqualsHighValue(
            TestInfo testInfo) {
        LedgerMonolithController controller = controllerWithRealValidator();
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromAccountNum()).thenReturn("0987654321");
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getAmount()).thenReturn(HIGH_VALUE_THRESHOLD_CENTS);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        final ResponseEntity actualResult =
                controller.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transactionRepository).save(transaction);
    }

    private LedgerMonolithController controllerWithRealValidator() {
        return new LedgerMonolithController(
                "test-pubkey",
                ledgerReaderCache,
                verifier,
                transactionRepository,
                new TransactionValidator(),
                ledgerReader,
                LOCAL_ROUTING_NUM,
                VERSION);
    }

    private void stubValidOutboundTransaction(int amount, TestInfo testInfo) {
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(TO_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(TO_ROUTING_NUM);
        when(transaction.getAmount()).thenReturn(amount);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
    }

    private void assertHighValueOutboundNotPersisted(
            int amount, TestInfo testInfo) {
        LedgerMonolithController controller = controllerWithRealValidator();
        stubValidOutboundTransaction(amount, testInfo);

        final ResponseEntity actualResult =
                controller.addTransaction(BEARER_TOKEN, transaction);

        assertNotNull(actualResult);
        assertEquals(EXCEPTION_MESSAGE_MANUAL_REVIEW_REQUIRED,
                actualResult.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        verify(transactionRepository, never()).save(transaction);
    }
}
