package com.rateforge.security

import org.springframework.context.annotation.Configuration

/**
 * Actuator endpoints are restricted to the management port (9091) which is not
 * exposed externally in the deployment topology. Servlet-based security filters
 * are intentionally omitted — this is a gRPC-only service with no embedded
 * servlet container.
 */
@Configuration
class ActuatorSecurityConfig
