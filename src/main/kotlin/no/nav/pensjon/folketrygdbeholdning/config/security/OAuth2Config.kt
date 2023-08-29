package no.nav.pensjon.folketrygdbeholdning.config.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.web.client.RestTemplate

@Configuration
@Profile("!disable-sec")
class OAuth2Config(@Value("\${pen.proxy.fss.client.registration}") val clientRegistration: String) {

    //The OAuth2AuthorizedClientManager is responsible for managing the authorization (or re-authorization) of an
    // OAuth 2.0 Client, in collaboration with one or more OAuth2AuthorizedClientProvider(s).
    @Bean
    fun authorizedClientManager(
        clientRegistrationRepository: ClientRegistrationRepository,
        authorizedClientRepository: OAuth2AuthorizedClientRepository
    ): OAuth2AuthorizedClientManager =
        DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientRepository).apply {
            this.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build()
            )
    }

    @Bean
    fun restTemplateWithAzureClientCredentials(authorizedClientManager: OAuth2AuthorizedClientManager): RestTemplate {
        return RestTemplateBuilder()
            .additionalInterceptors(createInterceptor(authorizedClientManager))
            .build()
    }

    private fun createInterceptor(authorizedClientManager: OAuth2AuthorizedClientManager) =
        ClientHttpRequestInterceptor { request: HttpRequest, body: ByteArray?, execution: ClientHttpRequestExecution ->
            authorizedClientManager.authorize(
                OAuth2AuthorizeRequest
                    .withClientRegistrationId(clientRegistration)
                    .principal("anonymous")
                    .build())!!
                .let { authorizedClient ->
                    request.headers.setBearerAuth(authorizedClient.accessToken.tokenValue)
                    execution.execute(request, body!!)
                }
        }
}