package com.example

def login(String apiURL, String credentialsId) {
    withCredentials([string(credentialsId: credentialsId, variable: 'authToken')]) {
        sh """
            set +x
            oc login --token=${authToken} ${apiURL} --insecure-skip-tls-verify >/dev/null 2>&1 || echo 'OpenShift login failed'
        """
    }
}

/**
   * Runs mvn clean package on application code
   */
def unitTestAndPackageJar(String pomPath, String mavenArgs) {
    stage('Unit Test & Package Jar'){
        print "Packaging Jar..."
        sh """
            mvn -f ${pomPath} ${mavenArgs} -U clean package
        """
    }
}

/**
   * Tag image across projects
   */
def tagImage(String sourceProject, String sourceImage, String sourceTag, String destProject, String destImage, String destTag) {
    stage('Tag Image'){
        print "Tagging Image..."
        sh """
            oc tag ${sourceProject}/${sourceImage}:${sourceTag} ${destProject}/${destImage}:${destTag}
        """
    }
}

/**
   * Processes and applies a template and then starts a binary build from file
   */
def processTemplateAndStartBuild(String templatePath,  String parameters, String project,
    String buildConfigName, String jarPath) {

    stage("OCP Build"){
        print "Building in OpenShift..."
        sh """
            oc process -f ${templatePath} ${parameters} -n ${project} | oc apply -f - -n ${project}
            oc start-build ${buildConfigName} --from-file=${jarPath} --follow -n ${project}
        """
    }
}


/**
   * Processes and applies a template and then triggers a deployment
   */
def processTemplateAndDeploy(String ocpUrl, String authToken, String templatePath, String parameters, String project, String microservice) {

    stage("OCP Deploy"){
        print "Deploying in OpenShift..."
        sh """
            oc process -f ${templatePath} ${parameters} -n ${project} | oc apply -f - -n ${project}
        """

        openshiftVerifyDeployment(
            depCfg: microservice,
            namespace: project,
            replicaCount: '1',
            apiURL: ocpUrl,
            authToken: authToken
        )
    }
}

def blueGreenDeploy(String ocpUrl, String authToken, String microservice, String project, String templatesDir, String imageTag) {

    stage("A/B Deploy in ${project}"){

        // Deploy the "green" image
        processTemplateAndDeploy(ocpUrl, authToken, "${templatesDir}/deploy-service-template.yaml",
            "APPLICATION_NAME=${microservice}-green IMAGE_TAG=${imageTag}", project, microservice)

        input 'Begin A/B Testing?'

        try {
            // Deploy split route
            sh """
                oc process -f ${templatesDir}/route-split-template.yml APPLICATION_NAME=${microservice} \
                    MAJOR_SERVICE_NAME=${microservice} MINOR_SERVICE_NAME=${microservice}-green -n ${project} | oc apply -f - -n ${project}
            """

            input 'Increase percentages to 50/50?'
            sh """
                oc patch route ${microservice} -p '{"spec":{"to":{"kind": "Service","name": "${microservice}","weight": 50},"alternateBackends": [{"kind": "Service","name": "${microservice}-green","weight": 50}]}}' -n ${project}
            """

            input 'Increase percentages to 0/100?'
            sh """
                oc patch route ${microservice} -p '{"spec":{"to":{"kind": "Service","name": "${microservice}","weight": 0},"alternateBackends": [{"kind": "Service","name": "${microservice}-green","weight": 100}]}}' -n ${project}
            """

            input 'Rollout?'

            // Now to do the roll out, first updating the dc that is receiving no traffic with the correct image
            processTemplateAndDeploy(ocpUrl, authToken, "${templatesDir}/deploy-service-template.yaml",
                "APPLICATION_NAME=${microservice} IMAGE_TAG=${imageTag}", project, microservice)

            aborted = false

        } catch(err) {

            aborted = true

        } finally {
            // Switch the route back to the "blue" deployment and delete green
            sh """
                oc process -f ${templatesDir}/route-template.yml \
                    APPLICATION_NAME=${microservice} -n ${project} | oc apply -f - -n ${project}

                oc delete svc ${microservice}-green -n ${project}
                oc delete dc ${microservice}-green -n ${project}
            """
            if(aborted){
                error("A/B Testing Aborted")
            }

        }
    }
}

def downloadDeploymentAndBlueGreenDeploy(String microservice, String project, String ocpUrl, String ocpAuthTokenCredentialId,
    String artifactoryCredentialsId, String registryUrl, String imageTag, String additionalArgs) {

    stage("A/B Deploy in ${project}"){


        login(ocpUrl, ocpAuthTokenCredentialId)

        // Deploy Green on side
        sh """
            cat openshift/config-maps/${project}/config.yml | sed -e "s/name: ${microservice}/name: ${microservice}-green/" | oc apply -f - -n dsm-release

            oc process -f openshift/templates/mplat-dsm-deploy-service-template.yml APPLICATION_NAME=${microservice}-green \
                IMAGE=${registryUrl}/${microservice}:${imageTag} -n ${project} | oc apply -f - -n ${project}
        """

        triggerDeploymentAndVerify(ocpAuthTokenCredentialId, microservice + "-green", project, ocpUrl, '1')

        input 'Begin A/B Testing?'

        try {
            // Deploy split route
            sh """
                oc process -f openshift/templates/mplat-dsm-route-split-template.yml APPLICATION_NAME=${microservice} \
                    MAJOR_SERVICE_NAME=${microservice} MINOR_SERVICE_NAME=${microservice}-green -n ${project} | oc apply -f - -n ${project}
            """

            input 'Increase percentages to 50/50?'
            sh """
                oc patch route ${microservice} -p '{"spec":{"to":{"kind": "Service","name": "${microservice}","weight": 50},"alternateBackends": [{"kind": "Service","name": "${microservice}-green","weight": 50}]}}' -n dsm-release
            """

            input 'Increase percentages to 0/100?'
            sh """
                oc patch route ${microservice} -p '{"spec":{"to":{"kind": "Service","name": "${microservice}","weight": 0},"alternateBackends": [{"kind": "Service","name": "${microservice}-green","weight": 100}]}}' -n dsm-release
            """

            input 'Rollout?'

            // Now to do the roll out, first updating the dc that is receiving no traffic with the correct image
            sh """
                oc apply -f openshift/config-maps/${project}/config.yml

                oc process -f openshift/templates/mplat-dsm-deploy-service-template.yml APPLICATION_NAME=${microservice} \
                    IMAGE=${registryUrl}/${microservice}:${imageTag} -n ${project} | oc apply -f - -n ${project}
            """
            triggerDeploymentAndVerify(ocpAuthTokenCredentialId, microservice, project, ocpUrl, '1')

            aborted = false

        } catch(err) {

            aborted = true

        } finally {
            // If any of the above steps fail we want to switch the route back to the "blue" deployment
            sh """
                oc process -f openshift/templates/mplat-dsm-route-template.yml \
                    APPLICATION_NAME=${microservice} -n ${project} | oc apply -f - -n ${project}

                oc delete svc ${microservice}-green -n ${project}
                oc delete dc ${microservice}-green -n ${project}
                oc delete configmap ${microservice}-green -n ${project}
            """
            if(aborted){
                error("A/B Testing Aborted")
            }

        }
    }
}

def sonar(String mavenCredentialsId, String pomPath, String mavenArgs, String profile,
    String branch, String sonarUrl, String sonarCredentialsId, boolean breakBuild){

    stage('Sonar'){
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: mavenCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
            sh """
                mvn -f ${pomPath} ${mavenArgs} \
                    -DartifactoryUser=${user} -DartifactoryPassword=${pass} \
                    clean install sonar:sonar -DskipITs -Psonar,artifactory,metrics \
                    -Dsonar.profile="${profile}" -Dsonar.branch="${branch}"
            """
        }

        pom = readMavenPom file: pomPath
        projectKey = "${pom.groupId}:${pom.artifactId}:${branch}"
        qualityGateStatus = processSonarQubeResults(sonarUrl, projectKey, sonarCredentialsId)

        if (!qualityGateStatus.contains("OK")) {
            if (breakBuild) {
                error("SonarQube quality gates failed")
            } else {
                print "Setting build status to unstable due SonarQube Quality Gate: ${qualityGateStatus}"
                currentBuild.result='UNSTABLE'
            }
        }
    }
}

/**
 * Parses logs for the SonarQube Urls, polls for sonar processing to finish
 * and returns Quality Gate Status [OK, WARN, ERROR, NONE]
 * Should be called directly after sonar stage
 */
def processSonarQubeResults(String sonarUrl, String projectKey, String sonarCredentialsId) {

    String sonarQubeTaskURL = null;
    String reportURL = null;

    // Get the most recent 150 log lines in a list
    def list = currentBuild.rawBuild.getLog(200)

    String taskLinePrefix = "[INFO] More about the report processing at "
    String reportLinePrefix = "[INFO] ANALYSIS SUCCESSFUL, you can browse "

    for (int i=list.size()-1; i>0; i--) {
        String line = list[i]
        if (line.contains(taskLinePrefix)) {
            sonarQubeTaskURL = line.substring(taskLinePrefix.length())
        }
        if (line.contains(reportLinePrefix)) {
            reportURL = line.substring(reportLinePrefix.length())
        }
    }

    if (sonarQubeTaskURL == null) {
        print "sonarQubeTaskURL url not found"
        return
    }

    print "SonarQube analysis report can be found at: " + reportURL

    String status = getSonarQubeTaskStatus(sonarQubeTaskURL)

    while (status == "PENDING" || status == "IN_PROGRESS") {
        sleep 1
        status = getSonarQubeTaskStatus(sonarQubeTaskURL)
    }

    if (status == "FAILED" || status == "CANCELED") {
        print "SonarQube analysis failed, please see to the SonarQube analysis: ${reportURL}"
        return "NONE"
    }

    qualityGateUrl = "${sonarUrl}/api/qualitygates/project_status?projectKey=${projectKey}"
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: sonarCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
        output = sh (
            script:"""
              curl -sk -u "${user}":"${pass}" ${qualityGateUrl}
            """,
            returnStdout: true
        )
        jsonOutput = readJSON text: output
        status = jsonOutput["projectStatus"]["status"]
        print "SonarQube status is " + status
        return status
    }
}

/**
 * Call into SonarQube and to get status of a project analysis
 * Could return PENDING, IN_PROGRESS FAILED, CANCELED, SUCCESS
 */
def getSonarQubeTaskStatus(String statusUrl) {
    print "Requesting SonarQube status at " + statusUrl

    output = sh (
        script:"""
          curl -sk ${statusUrl}
        """,
        returnStdout: true
    )

    jsonOutput = readJSON text: output
    taskStatus = jsonOutput["task"]["status"]
    print "SonarQube task status is " + taskStatus
    return taskStatus
}


/**
 * Runs JMeter tests directly on agent, be sure the image has the binaries downloaded and JMETER_HOME set
 */
def jmeterTest(String testFilePath, String propertiesFilePath, String hostName, String threads, String count){
    stage('Stress Tests') {
        sh """
            ${JMETER_HOME}/bin/jmeter -n -t "${testFilePath}" -l "jmeter-results.jtl" \
                -p "${testFilePath}" -j /dev/stdout -Jhostname=${hostName} \
                -Jthreads=${threads} -Jcount=${count}
        """

        performanceReport compareBuildPrevious: false,
            configType: 'ART',
            errorFailedThreshold: 0,
            errorUnstableResponseTimeThreshold: '',
            errorUnstableThreshold: 0,
            failBuildIfNoResultFile: false,
            ignoreFailedBuild: false,
            ignoreUnstableBuild: true,
            modeOfThreshold: false,
            modePerformancePerTestCase: true,
            modeThroughput: true,
            nthBuildNumber: 0,
            parsers: [[$class: 'JMeterParser', glob: 'jmeter-results.jtl']],
            relativeFailedThresholdNegative: 0,
            relativeFailedThresholdPositive: 0,
            relativeUnstableThresholdNegative: 0,
            relativeUnstableThresholdPositive: 0
    }
}



def downloadDeploymentAndDeploy(String microservice, String project, String ocpUrl, String ocpAuthTokenCredentialId,
    String artifactoryCredentialsId, String distribuitionZipUrl, String artifactoryUrl, String imageTag, String additionalArgs) {

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: artifactoryCredentialsId, passwordVariable: 'pass', usernameVariable: 'user']]) {
        print 'Downloading distribution.zip for deployment'
        sh """
            curl ${additionalArgs} --user "${user}":"${pass}" -L -o distro.zip ${distribuitionZipUrl}
            unzip -o distro.zip
        """
    }

    login(ocpUrl, ocpAuthTokenCredentialId)

    sh """
        oc apply -f openshift/config-maps/${project}/ -R -n ${project}
        oc apply -f openshift/deployment.yml -n ${project}
    """

    withCredentials([string(credentialsId: ocpAuthTokenCredentialId, variable: 'authToken')]) {
        openshiftDeploy(
            depCfg: microservice,
            namespace: project,
            apiURL: ocpUrl,
            authToken: authToken
        )

        openshiftVerifyDeployment(
            depCfg: microservice,
            namespace: project,
            replicaCount: '1',
            apiURL: ocpUrl,
            authToken: authToken
        )
    }
}
