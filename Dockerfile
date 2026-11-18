# Run the TSI Compass WAR on Jetty with Java 17.
FROM jetty:11-jdk17

# Set timezone non-interactively
ENV TZ=Asia/Kolkata

# Jetty paths used by the official image.
ENV JETTY_BASE=/var/lib/jetty
ENV JETTY_HOME=/usr/local/jetty
ENV JETTY_RUN=/tmp/jetty

# Mitigation for CVE-2026-114938 ("Java4Shell"): heap out-of-bounds write in the
# AVX-512-accelerated Base64 decoder intrinsic in OpenJDK 17.0.6-17.0.21/21.x/25.x.
# Forces the plain-Java Base64 fallback until the base image ships a patched JDK build.
# Revisit/remove once the jetty base image is upgraded past the affected JDK range.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

RUN java -jar "$JETTY_HOME/start.jar" --add-modules=http,jdbc,jndi,deploy

USER root
RUN mkdir -p /var/lib/tsi-compass/exports/evidence \
             /var/lib/tsi-compass/exports/policies \
             /var/lib/tsi-compass/exports/incident_docs \
             /var/lib/tsi-compass/exports/kb_docs \
             /var/lib/tsi-compass/exports/campaign_docs \
             /var/lib/tsi-compass/exports/ticket_attachments \
             /var/lib/tsi-compass/exports/change_request_attachments \
    && chmod -R 777 /var/lib/tsi-compass

# Switch to the 'jetty' user
USER jetty

COPY target/tsi-compass.war ${JETTY_BASE}/webapps/root.war

EXPOSE 8080
