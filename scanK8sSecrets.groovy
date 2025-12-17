import org.yaml.snakeyaml.Yaml

// ==================== CONFIGURATION ====================
def USER = ''  // SSH user for connecting to hosts
def HOSTS = []  // Unified list of BASTION/SERVER hosts (e.g., ['host1.example.com', 'host2.example.com'])

// Load passwords to find - will be loaded from passwordsToFind.groovy at runtime
def passwordsToFind = ['ducptm']

// ==================== HELPER FUNCTIONS ====================

def getAllNamespaces(String user, String host) {
    """Get all namespaces except those starting with 'openshift'"""
    def output = sh(
        script: """
            ssh ${user}@${host} 'kubectl get namespaces -o name | cut -d"/" -f2 | grep -vE "^(openshift|default|assisted|dcit|health|keycloak|kube|kong|ocp|open|twistlock|micro)"'
        """,
        returnStdout: true
    ).trim()

    if (!output) {
        return []
    }

    // Split by newlines and filter out empty strings
    def namespaces = output.split('\n').collect { it.trim() }.findAll { it }
    return namespaces
}

def scanConfigMapsForPasswords(String user, String host, String namespace, List passwordsToFind) {
    """Scan all ConfigMaps in a namespace for matching passwords"""
    def findings = []

    try {
        // Get list of ConfigMaps
        def output = sh(
            script: """
                ssh ${user}@${host} 'kubectl get configmap -n ${namespace} -o name | cut -d"/" -f2'
            """,
            returnStdout: true
        ).trim()

        if (!output) {
            return findings
        }

        // Split by newlines and filter out empty strings
        def configMapList = output.split('\n').collect { it.trim() }.findAll { it }

        configMapList.each { configMapName ->

            try {
                // Get ConfigMap YAML
                def yamlString = sh(
                    script: """
                        ssh ${user}@${host} 'kubectl get configmap ${configMapName} -n ${namespace} -o yaml'
                    """,
                    returnStdout: true
                ).trim()

                def yaml = new Yaml().load(yamlString)

                if (yaml?.data) {
                    yaml.data.each { key, value ->
                        if (value instanceof String) {
                            // Check if value matches any password in the list
                            passwordsToFind.each { password ->
                                if (!password) return

                                def foundInMultiline = false

                                // Handle KEY=VALUE format (multi-line properties)
                                if (value.contains('=')) {
                                    def lines = value.split('\n').collect { it.trim() }.findAll { it && !it.startsWith('#') }
                                    lines.each { line ->
                                        def parts = line.split('=', 2)
                                        if (parts.length == 2 && parts[1].toLowerCase().contains(password.toLowerCase())) {
                                            findings << [
                                                type: 'ConfigMap',
                                                name: configMapName,
                                                key: "${key}.${parts[0]}",
                                                password: password,
                                                namespace: namespace
                                            ]
                                            foundInMultiline = true
                                        }
                                    }
                                }

                                // Handle KEY:VALUE format (YAML)
                                if (value.contains(':')) {
                                    def lines = value.split('\n').collect { it.trim() }.findAll { it && !it.startsWith('#') }
                                    lines.each { line ->
                                        def parts = line.split(':', 2)
                                        if (parts.length == 2 && parts[1].trim().toLowerCase().contains(password.toLowerCase())) {
                                            findings << [
                                                type: 'ConfigMap',
                                                name: configMapName,
                                                key: "${key}.${parts[0].trim()}",
                                                password: password,
                                                namespace: namespace
                                            ]
                                            foundInMultiline = true
                                        }
                                    }
                                }

                                // Fallback: Direct value match if not found in structured format
                                if (!foundInMultiline && value.toLowerCase().contains(password.toLowerCase())) {
                                    findings << [
                                        type: 'ConfigMap',
                                        name: configMapName,
                                        key: key,
                                        password: password,
                                        namespace: namespace
                                    ]
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                echo "  ⚠️  Error scanning ConfigMap ${configMapName}: ${e.message}"
            }
        }
    } catch (Exception e) {
        echo "  ⚠️  Error listing ConfigMaps in namespace ${namespace}: ${e.message}"
    }

    return findings
}

def scanSecretsForPasswords(String user, String host, String namespace, List passwordsToFind) {
    """Scan all Secrets in a namespace for matching passwords"""
    def findings = []

    try {
        // Get list of Secrets
        def output = sh(
            script: """
                ssh ${user}@${host} 'kubectl get secret -n ${namespace} -o name | cut -d"/" -f2'
            """,
            returnStdout: true
        ).trim()

        if (!output) {
            return findings
        }

        // Split by newlines and filter out empty strings
        def secretList = output.split('\n').collect { it.trim() }.findAll { it }

        secretList.each { secretName ->

            try {
                // Get Secret YAML
                def yamlString = sh(
                    script: """
                        ssh ${user}@${host} 'kubectl get secret ${secretName} -n ${namespace} -o yaml'
                    """,
                    returnStdout: true
                ).trim()

                def yaml = new Yaml().load(yamlString)

                if (yaml?.data) {
                    yaml.data.each { key, encodedValue ->
                        if (encodedValue instanceof String) {
                            try {
                                // Decode base64 value
                                def decodedValue = new String(encodedValue.decodeBase64())

                                // Check if decoded value matches any password in the list
                                passwordsToFind.each { password ->
                                    if (!password) return

                                    def foundInMultiline = false

                                    // Handle KEY=VALUE format (multi-line properties)
                                    if (decodedValue.contains('=')) {
                                        def lines = decodedValue.split('\n').collect { it.trim() }.findAll { it && !it.startsWith('#') }
                                        lines.each { line ->
                                            def parts = line.split('=', 2)
                                            if (parts.length == 2 && parts[1].toLowerCase().contains(password.toLowerCase())) {
                                                findings << [
                                                    type: 'Secret',
                                                    name: secretName,
                                                    key: "${key}.${parts[0]}",
                                                    password: password,
                                                    namespace: namespace
                                                ]
                                                foundInMultiline = true
                                            }
                                        }
                                    }

                                    // Handle KEY:VALUE format (YAML)
                                    if (decodedValue.contains(':')) {
                                        def lines = decodedValue.split('\n').collect { it.trim() }.findAll { it && !it.startsWith('#') }
                                        lines.each { line ->
                                            def parts = line.split(':', 2)
                                            if (parts.length == 2 && parts[1].trim().toLowerCase().contains(password.toLowerCase())) {
                                                findings << [
                                                    type: 'Secret',
                                                    name: secretName,
                                                    key: "${key}.${parts[0].trim()}",
                                                    password: password,
                                                    namespace: namespace
                                                ]
                                                foundInMultiline = true
                                            }
                                        }
                                    }

                                    // Fallback: Direct value match if not found in structured format
                                    if (!foundInMultiline && decodedValue.toLowerCase().contains(password.toLowerCase())) {
                                        findings << [
                                            type: 'Secret',
                                            name: secretName,
                                            key: key,
                                            password: password,
                                            namespace: namespace
                                        ]
                                    }
                                }
                            } catch (Exception decodeError) {
                                // Skip if base64 decode fails
                            }
                        }
                    }
                }
            } catch (Exception e) {
                echo "  ⚠️  Error scanning Secret ${secretName}: ${e.message}"
            }
        }
    } catch (Exception e) {
        echo "  ⚠️  Error listing Secrets in namespace ${namespace}: ${e.message}"
    }

    return findings
}

def printFindings(String host, Map<String, List> findingsByNamespace) {
    """Save findings to artifact file and print summary"""

    if (findingsByNamespace.isEmpty()) {
        echo "✓ No matching passwords found on host: ${host}"
        return
    }

    // Build file content
    def fileContent = new StringBuilder()
    fileContent.append("=" * 80).append("\n")
    fileContent.append("Password Findings Report - Host: ${host}\n")
    fileContent.append("=" * 80).append("\n\n")

    def totalFindings = 0

    findingsByNamespace.each { namespace, findings ->
        fileContent.append("Namespace: ${namespace}\n")
        fileContent.append("-" * 80).append("\n")

        findings.each { finding ->
            // Format: ConfigMap: configmap-name ----- key.subkey = password
            fileContent.append("${finding.type}: ${finding.name} ----- ${finding.key} = ${finding.password}\n")
            totalFindings++
        }

        fileContent.append("\n")
    }

    fileContent.append("=" * 80).append("\n")
    fileContent.append("Total findings: ${totalFindings}\n")
    fileContent.append("=" * 80).append("\n")

    // Save to file
    def fileName = "password-findings-${host.replaceAll('[^a-zA-Z0-9-]', '_')}-${BUILD_NUMBER}.txt"
    writeFile file: fileName, text: fileContent.toString()

    // Archive as Jenkins artifact
    archiveArtifacts artifacts: fileName, allowEmptyArchive: false

    // Print summary to console
    echo """
╔════════════════════════════════════════════════════════════════╗
║  🔍 Password Findings on Host: ${host.padRight(30)} ║
╚════════════════════════════════════════════════════════════════╝

  📊 Total findings: ${totalFindings}
  📦 Namespaces scanned: ${findingsByNamespace.size()}
  📄 Results saved to artifact: ${fileName}

  ✓ You can download the full report from Jenkins artifacts
"""
}

// ==================== MAIN PIPELINE ====================

node("built-in") {

    // Load passwords from passwordsToFind.groovy
    stage("Load Configuration") {
        echo "📥 Loading password list from passwordsToFind.groovy..."

        def passwordsScript = ['ducptm']
        passwordsToFind = passwordsScript

        echo "✓ Loaded ${passwordsToFind?.size() ?: 0} password(s) to search for"
    }

    // Validate configuration
    if (!HOSTS || HOSTS.isEmpty()) {
        error "❌ HOSTS list is empty. Please configure at least one host."
    }

    if (!passwordsToFind || passwordsToFind.isEmpty()) {
        error "❌ passwordsToFind list is empty. Please add passwords to search for in passwordsToFind.groovy"
    }

    echo """
╔════════════════════════════════════════════════════════════════╗
║         🔐 Kubernetes Password Scanner                        ║
║         Scanning ${HOSTS.size()} host(s) for ${passwordsToFind.size()} password(s)          ║
╚════════════════════════════════════════════════════════════════╝

Hosts: ${HOSTS.join(', ')}
Passwords to find: ${passwordsToFind.size()} password(s)
"""

    def allFindings = [:]
    def parallelStages = [:]

    // Build parallel stages for each host
    HOSTS.each { host ->
        // Capture host variable in closure scope
        def currentHost = host

        parallelStages["Scan Host: ${currentHost}"] = {
            stage("Scan: ${currentHost}") {
                echo "🖥️  Starting scan on host: ${currentHost}"

                try {
                    // Get all namespaces (excluding openshift*)
                    def namespaces = getAllNamespaces(USER, currentHost)
                    echo "  ✓ Found ${namespaces.size()} namespace(s) to scan"

                    def hostFindings = [:]

                    namespaces.each { namespace ->
                        echo "  📦 Scanning namespace: ${namespace}"

                        // Scan ConfigMaps
                        def configMapFindings = scanConfigMapsForPasswords(USER, currentHost, namespace, passwordsToFind)

                        // Scan Secrets
                        def secretFindings = scanSecretsForPasswords(USER, currentHost, namespace, passwordsToFind)

                        def allNamespaceFindings = configMapFindings + secretFindings

                        if (allNamespaceFindings) {
                            hostFindings[namespace] = allNamespaceFindings
                            echo "    ✓ Found ${allNamespaceFindings.size()} matching password(s)"
                        }
                    }

                    // Store findings for this host
                    // Safe without synchronization since each host writes to its own unique key
                    allFindings[currentHost] = hostFindings

                    // Print findings for this host
                    printFindings(currentHost, hostFindings)

                } catch (Exception e) {
                    echo "❌ Error scanning host ${currentHost}: ${e.message}"
                    currentBuild.result = 'UNSTABLE'
                }
            }
        }
    }

    // Execute all host scans in parallel
    stage("Parallel Host Scanning") {
        echo "🚀 Starting parallel scan of ${HOSTS.size()} host(s)..."
        parallel parallelStages
        echo "✓ Parallel scanning completed"
    }

    // Final summary stage
    stage("Summary") {
        echo """

╔════════════════════════════════════════════════════════════════╗
║                    📊 SCAN SUMMARY                            ║
╚════════════════════════════════════════════════════════════════╝
"""

        def grandTotal = 0
        def summaryContent = new StringBuilder()

        summaryContent.append("=" * 100).append("\n")
        summaryContent.append("KUBERNETES PASSWORD SCAN - CONSOLIDATED REPORT\n")
        summaryContent.append("Build: ${BUILD_NUMBER}\n")
        summaryContent.append("Date: ${new Date()}\n")
        summaryContent.append("=" * 100).append("\n\n")

        allFindings.each { host, findingsByNamespace ->
            def hostTotal = findingsByNamespace.values().flatten().size()
            grandTotal += hostTotal

            summaryContent.append("\n")
            summaryContent.append("*" * 100).append("\n")
            summaryContent.append("HOST: ${host}\n")
            summaryContent.append("*" * 100).append("\n\n")

            if (findingsByNamespace.isEmpty()) {
                summaryContent.append("✓ No matching passwords found on this host\n")
            } else {
                findingsByNamespace.each { namespace, findings ->
                    summaryContent.append("Namespace: ${namespace}\n")
                    summaryContent.append("-" * 100).append("\n")

                    findings.each { finding ->
                        summaryContent.append("${finding.type}: ${finding.name} ----- ${finding.key} = ${finding.password}\n")
                    }
                    summaryContent.append("\n")
                }
            }

            echo "  ${host}: ${hostTotal} finding(s)"
        }

        summaryContent.append("\n")
        summaryContent.append("=" * 100).append("\n")
        summaryContent.append("SUMMARY\n")
        summaryContent.append("=" * 100).append("\n")
        summaryContent.append("Total hosts scanned: ${HOSTS.size()}\n")
        summaryContent.append("Total findings across all hosts: ${grandTotal}\n")
        summaryContent.append("=" * 100).append("\n")

        // Save consolidated report
        if (grandTotal > 0) {
            def summaryFileName = "password-findings-ALL-HOSTS-${BUILD_NUMBER}.txt"
            writeFile file: summaryFileName, text: summaryContent.toString()
            archiveArtifacts artifacts: summaryFileName, allowEmptyArchive: false

            echo """
╔════════════════════════════════════════════════════════════════╗
║  Total findings across all hosts: ${String.valueOf(grandTotal).padRight(28)} ║
╚════════════════════════════════════════════════════════════════╝

  ⚠️  Action required: Review and rotate the identified passwords
  📄 Consolidated report: ${summaryFileName}
  ✓ All reports saved as Jenkins artifacts
"""
        } else {
            echo """
╔════════════════════════════════════════════════════════════════╗
║  Total findings across all hosts: 0                           ║
╚════════════════════════════════════════════════════════════════╝

  ✓ No matching passwords found. Configuration appears secure.
"""
        }
    }
}
