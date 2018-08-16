package pksServiceBroker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.DefaultOAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.DefaultAccessTokenRequest;
import org.springframework.security.oauth2.client.token.grant.client.ClientCredentialsResourceDetails;
import org.springframework.security.oauth2.common.AuthenticationScheme;

@Configuration
public class CFRouteApiRestTemplate {
	
	@Value("${routereg.route_api_client.client_id}")
	private String routeApiClient;
	@Value("${routereg.route_api_client.client_secret}")
	private String routeApiClientSecret;
	@Value("${routereg.route_api_client.accessTokenUri}")
	private String tokenUrl;
	@Value("${routereg.route_api_client.grant_type}")
	private String grantType;

	protected OAuth2ProtectedResourceDetails resource() {
		ClientCredentialsResourceDetails resource = new ClientCredentialsResourceDetails();
		resource.setClientAuthenticationScheme(AuthenticationScheme.query);
		resource.setAccessTokenUri(tokenUrl);
		resource.setClientId(routeApiClient);
		resource.setClientSecret(routeApiClientSecret);
		resource.setGrantType(grantType);
		return resource;
	}

	@Bean(name="route")
	public OAuth2RestTemplate routeRestTemplate() {
		AccessTokenRequest atr = new DefaultAccessTokenRequest();
		return new OAuth2RestTemplate(resource(), new DefaultOAuth2ClientContext(atr));
	}
	
}
