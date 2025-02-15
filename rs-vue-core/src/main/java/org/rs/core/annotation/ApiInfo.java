package org.rs.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.ElementType;

@Target(value = {ElementType.TYPE,ElementType.METHOD})
@Retention(value = RetentionPolicy.RUNTIME)
@Documented
public @interface ApiInfo {
	public String value() default "";
	
	public boolean log() default true;
}
