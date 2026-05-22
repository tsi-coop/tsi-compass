# Run the TSI Compass WAR on Jetty with Java 17.
FROM jetty:11-jdk17

# Set timezone non-interactively
ENV TZ=Asia/Kolkata

# Jetty paths used by the official image.
ENV JETTY_BASE=/var/lib/jetty
ENV JETTY_HOME=/usr/local/jetty
ENV JETTY_RUN=/tmp/jetty

RUN java -jar "$JETTY_HOME/start.jar" --add-modules=http,jdbc,jndi,deploy

# Switch to the 'jetty' user
USER jetty

COPY target/tsi-compass.war ${JETTY_BASE}/webapps/root.war

EXPOSE 8080
