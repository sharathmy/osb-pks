package pksServiceBroker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.DefaultOAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.DefaultAccessTokenRequest;
import org.springframework.security.oauth2.client.token.grant.password.ResourceOwnerPasswordResourceDetails;
import org.springframework.security.oauth2.common.AuthenticationScheme;

@Configuration
class PKS_API_config {
	@Value("${pks.pks_api_client.name}")
	private String pksClient;
	@Value("${pks.pks_api_client.secret}")
	private String pksClientSecret;
	@Value("${pks.pks_api_client.accessTokenUri}")
	private String tokenUrl;
	@Value("${pks.pks_api_client.grant_type}")
	private String grant_type;

	@Value("${pks.pks_api_user.name}")
	private String pksUser;
	@Value("${pks.pks_api_user.password}")
	private String pksUserPass;

	protected OAuth2ProtectedResourceDetails resource() {
		ResourceOwnerPasswordResourceDetails resource = new ResourceOwnerPasswordResourceDetails();
		resource.setClientAuthenticationScheme(AuthenticationScheme.header);
		resource.setAuthenticationScheme(AuthenticationScheme.form);
		resource.setAccessTokenUri(tokenUrl);
		resource.setClientId(pksClient);
		resource.setGrantType("password");
		resource.setUsername(pksUser);
		resource.setPassword(pksUserPass);
		
		return resource;
	}

	@Bean(name="pks")
	public OAuth2RestTemplate pksRestTemplate() {
		AccessTokenRequest atr = new DefaultAccessTokenRequest();
		return new OAuth2RestTemplate(resource(), new DefaultOAuth2ClientContext(atr));
	}

}
