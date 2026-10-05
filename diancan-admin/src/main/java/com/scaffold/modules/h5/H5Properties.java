package com.scaffold.modules.h5;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Data @Component @ConfigurationProperties(prefix="restaurant.h5")
public class H5Properties {
    private boolean enabled=false;
    private String signingKey="";
    private String publicOrigin="";
}
