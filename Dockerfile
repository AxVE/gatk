# Using OpenJDK 8
FROM broadinstitute/gatk:gatkbase-1.2.1
ARG ZIPPATH

ADD $ZIPPATH /gatk.zip

RUN unzip /gatk.zip
ar

WORKDIR /gatk
RUN ln -s /gatk/$( find . -name "gatk*local.jar" ) gatk.jar
RUN ln -s /gatk/$( find . -name "gatk*spark.jar" ) gatk-spark.jar
# RUN /gatk/gradlew clean compileTestJava installAll localJar -Drelease=$DRELEASE

WORKDIR /root

# Make sure we can see a help message
RUN ln -sFv /gatk/gatk.jar
RUN java -jar gatk.jar -h

#Setup test data
WORKDIR /gatk
# Create link to where test data is expected
RUN ln -s /testdata src/test/resources

# Create a simple unit test runner
ENV CI true
RUN echo "cd /gatk/ && ./gradlew jacocoTestReport" >/root/run_unit_tests.sh

WORKDIR /root
RUN cp -r /root/run_unit_tests.sh /gatk
RUN cp -r gatk.jar /gatk
RUN cp -r install_R_packages.R /gatk

# RUN rm -r /gatk/src
# RUN rm -rf .gradle
# RUN rm -rf /gatk/.git
# RUN rm -rf /tmp/downloaded_packages/ /tmp/*.rds \
# RUN rm -rf /var/lib/apt/lists/*

WORKDIR /gatk