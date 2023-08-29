package no.nav.pensjon.folketrygdbeholdning

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import no.nav.pensjon.folketrygdbeholdning.annotations.SecurityDisabled
import no.nav.pensjon.folketrygdbeholdning.controllers.ApiController
import no.nav.pensjonsamhandling.maskinporten.validation.test.AutoConfigureMaskinportenValidator
import no.nav.pensjonsamhandling.maskinporten.validation.test.MaskinportenValidatorTokenGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.DefaultUriBuilderFactory
import java.time.LocalDate
import java.util.*

@SpringBootTest
@AutoConfigureMockMvc
@Import(RestTemplateTestConfig::class)
@AutoConfigureMaskinportenValidator
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SecurityDisabled
class ProxyMappingTest(
    @Autowired private val tokenGenerator: MaskinportenValidatorTokenGenerator,
    @Autowired private val mockMvc: MockMvc
) {

    private val wireMockServer = WireMockServer(8080)
    private val responseBody = """{"value": false }"""

    @BeforeAll
    fun setup() {
        wireMockServer.start()
    }

    @AfterAll
    fun tearDown() {
        wireMockServer.stop()
    }

    companion object {
        @JvmStatic
        private fun get500ServerErrors() = listOf(
            HttpStatus.BAD_GATEWAY,
            HttpStatus.NOT_IMPLEMENTED,
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.SERVICE_UNAVAILABLE
        )

    }

    @Test
    fun `test gets, request is forwarded with correct method, correlationId header and status - body and ok status is returned correct`() {
        val correlationId = UUID.randomUUID().toString()
        val fnr = "121212121212"
        val tpnr = "9999"
        val beholdningFom = LocalDate.of(2023, 12,12)
        val authorization = "bearer ${tokenGenerator.generateToken("nav:pensjon/v1/tpregisteret", "12345678910")}"

        stubFor(
            get(urlEqualTo("/api/samhandler/tjenestepensjon/forhold/$tpnr"))
                .withHeader(ApiController.CORRELATION_ID, equalTo(correlationId))
                .withHeader(ApiController.FNR, equalTo(fnr))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(authorization))
                .willReturn(aResponse().withBody(responseBody))
        )

        stubFor(
            get(urlEqualTo("/folketrygdbeholdning/beregning/"))
                .withHeader(ApiController.FNR, equalTo(fnr))
                .withHeader(ApiController.BEHOLDNING_FOM, equalTo(beholdningFom.toString()))
                .willReturn(aResponse().withBody(responseBody))

            )


        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/beregning")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header(ApiController.FNR, fnr)
                    .header(ApiController.TPNR, tpnr)
                    .header(ApiController.BEHOLDNING_FOM, beholdningFom)
                    .header(ApiController.CORRELATION_ID, correlationId)
            )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.content().string(responseBody))

    }

    @ParameterizedTest
    @MethodSource("get500ServerErrors")
    fun `test error from server returns as bad gateway`(error: HttpStatus) {
        val correlationId = UUID.randomUUID().toString()
        val fnr = "121212121212"
        val tpnr = "9999"
        val beholdningFom = LocalDate.of(2023, 12,12)
        val authorization = "bearer ${tokenGenerator.generateToken("nav:pensjon/v1/tpregisteret", "12345678910")}"

        stubFor(
            get(urlEqualTo("/api/samhandler/tjenestepensjon/forhold/$tpnr"))
                .withHeader(ApiController.CORRELATION_ID, equalTo(correlationId))
                .withHeader(ApiController.FNR, equalTo(fnr))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(authorization))
                .willReturn(aResponse().withStatus(error.value()))
        )

        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/beregning")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .header(ApiController.FNR, fnr)
                    .header(ApiController.TPNR, tpnr)
                    .header(ApiController.BEHOLDNING_FOM, beholdningFom)
                    .header(ApiController.CORRELATION_ID, correlationId)
            )
            .andExpect(MockMvcResultMatchers.status().isBadGateway)
    }
}

@TestConfiguration
class RestTemplateTestConfig(@Value("\${PEN_PROXY_FSS_URL}") private val proxyUrl: String) {
    @Bean
    fun restTemplate(): RestTemplate {
        return RestTemplateBuilder()
            .uriTemplateHandler(DefaultUriBuilderFactory(proxyUrl))
            .build()
    }
}