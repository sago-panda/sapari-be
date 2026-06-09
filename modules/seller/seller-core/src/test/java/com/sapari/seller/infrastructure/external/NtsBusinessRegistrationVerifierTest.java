package com.sapari.seller.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sapari.seller.application.port.SellerBusinessRegistrationVerification;

@DisplayName("국세청 사업자 진위확인 검증 테스트")
class NtsBusinessRegistrationVerifierTest {

    private static final String BASE_URL = "https://api.test";
    private static final String SERVICE_KEY = "test-service-key";
    private static final String BUSINESS_NUMBER = "1234567890";
    private static final String REPRESENTATIVE_NAME = "판매자";
    private static final LocalDate BUSINESS_START_DATE = LocalDate.of(2020, 1, 1);

    private MockRestServiceServer server;
    private NtsBusinessRegistrationVerifier verifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .build();
        verifier = new NtsBusinessRegistrationVerifier(
                restClient,
                new NtsBusinessRegistrationProperties(
                        BASE_URL,
                        SERVICE_KEY,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5)
                )
        );
    }

    @Test
    @DisplayName("진위확인 성공 및 계속사업자이면 가입 가능 상태를 반환한다")
    void verifyReturnsAvailableWhenRegistrationIsValidAndActive() {
        // given
        expectValidateResponse("""
                {
                  "status_code": "OK",
                  "valid_cnt": 1,
                  "request_cnt": 1,
                  "data": [
                    {
                      "b_no": "1234567890",
                      "valid": "01",
                      "valid_msg": "확인되었습니다.",
                      "status": {
                        "b_stt": "계속사업자",
                        "b_stt_cd": "01"
                      }
                    }
                  ]
                }
                """);

        // when
        SellerBusinessRegistrationVerification result = verifier.verify(
                BUSINESS_NUMBER,
                REPRESENTATIVE_NAME,
                BUSINESS_START_DATE
        );

        // then
        assertThat(result.registrationAvailable()).isTrue();
        assertThat(result.failureReason()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("대표자명 또는 개업일자가 일치하지 않으면 가입 불가 상태를 반환한다")
    void verifyReturnsInvalidWhenRegistrationDoesNotMatch() {
        // given
        expectValidateResponse("""
                {
                  "status_code": "OK",
                  "valid_cnt": 0,
                  "request_cnt": 1,
                  "data": [
                    {
                      "b_no": "1234567890",
                      "valid": "02",
                      "valid_msg": "확인할 수 없습니다."
                    }
                  ]
                }
                """);

        // when
        SellerBusinessRegistrationVerification result = verifier.verify(
                BUSINESS_NUMBER,
                REPRESENTATIVE_NAME,
                BUSINESS_START_DATE
        );

        // then
        assertThat(result.registrationAvailable()).isFalse();
        assertThat(result.failureReason())
                .isEqualTo(SellerBusinessRegistrationVerification.FailureReason.INVALID_OR_INACTIVE);
        server.verify();
    }

    @Test
    @DisplayName("진위확인은 성공했지만 계속사업자가 아니면 가입 불가 상태를 반환한다")
    void verifyReturnsInvalidWhenBusinessStatusIsNotActive() {
        // given
        expectValidateResponse("""
                {
                  "status_code": "OK",
                  "valid_cnt": 1,
                  "request_cnt": 1,
                  "data": [
                    {
                      "b_no": "1234567890",
                      "valid": "01",
                      "valid_msg": "확인되었습니다.",
                      "status": {
                        "b_stt": "폐업자",
                        "b_stt_cd": "03"
                      }
                    }
                  ]
                }
                """);

        // when
        SellerBusinessRegistrationVerification result = verifier.verify(
                BUSINESS_NUMBER,
                REPRESENTATIVE_NAME,
                BUSINESS_START_DATE
        );

        // then
        assertThat(result.registrationAvailable()).isFalse();
        assertThat(result.failureReason())
                .isEqualTo(SellerBusinessRegistrationVerification.FailureReason.INVALID_OR_INACTIVE);
        server.verify();
    }

    @Test
    @DisplayName("국세청 응답 상태가 OK가 아니면 조회 불가 상태를 반환한다")
    void verifyReturnsUnavailableWhenStatusCodeIsNotOk() {
        // given
        expectValidateResponse("""
                {
                  "status_code": "BAD_JSON_REQUEST"
                }
                """);

        // when
        SellerBusinessRegistrationVerification result = verifier.verify(
                BUSINESS_NUMBER,
                REPRESENTATIVE_NAME,
                BUSINESS_START_DATE
        );

        // then
        assertThat(result.registrationAvailable()).isFalse();
        assertThat(result.failureReason()).isEqualTo(SellerBusinessRegistrationVerification.FailureReason.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("국세청 API 호출에 실패하면 조회 불가 상태를 반환한다")
    void verifyReturnsUnavailableWhenApiCallFails() {
        // given
        server.expect(once(), requestTo(BASE_URL + "/validate?serviceKey=" + SERVICE_KEY))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        // when
        SellerBusinessRegistrationVerification result = verifier.verify(
                BUSINESS_NUMBER,
                REPRESENTATIVE_NAME,
                BUSINESS_START_DATE
        );

        // then
        assertThat(result.registrationAvailable()).isFalse();
        assertThat(result.failureReason()).isEqualTo(SellerBusinessRegistrationVerification.FailureReason.UNAVAILABLE);
        server.verify();
    }

    private void expectValidateResponse(String responseBody) {
        server.expect(once(), requestTo(BASE_URL + "/validate?serviceKey=" + SERVICE_KEY))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "businesses": [
                            {
                              "b_no": "1234567890",
                              "start_dt": "20200101",
                              "p_nm": "판매자"
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }
}
