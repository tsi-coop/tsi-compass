# Run the TSI Compass WAR on Jetty with Java 17.
FROM jetty:11-jdk17

# Set timezone non-interactively
ENV TZ=Asia/Kolkata

# Jetty paths used by the official image.
ENV JETTY_BASE=/var/lib/jetty
ENV JETTY_HOME=/usr/local/jetty
ENV JETTY_RUN=/tmp/jetty

RUN java -jar "$JETTY_HOME/start.jar" --add-modules=http,jdbc,jndi,deploy

USER root
RUN mkdir -p /var/lib/tsi-compass/exports/evidence \
             /var/lib/tsi-compass/exports/policies \
             /var/lib/tsi-compass/exports/incident_docs \
             /var/lib/tsi-compass/exports/kb_docs \
             /var/lib/tsi-compass/exports/campaign_docs \
    && chmod -R 777 /var/lib/tsi-compass

# Switch to the 'jetty' user
USER jetty

COPY target/tsi-compass.war ${JETTY_BASE}/webapps/root.war

EXPOSE 8080
