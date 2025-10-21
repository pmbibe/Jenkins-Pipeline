

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

def USER = ''
def SERVER = ''
def NAMESPACE = ''
def passwordResults = []
// Input: List of variable names to exclude from masking (format: module_name.key_name)
def exclusionList = [
    // Example: 'configmap1.DB',
    // Example: 'configmap2.API'
]
@NonCPS
def dumpYaml(yamlObject) {
    DumperOptions options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
    options.setPrettyFlow(true)
    options.setIndent(2)
    options.setDefaultScalarStyle(DumperOptions.ScalarStyle.LITERAL)
    options.setSplitLines(false)
    options.setWidth(4096)
    
    Yaml yamlDumper = new Yaml(options)
    return yamlDumper.dump(yamlObject)
}

@NonCPS
def fixNewlines(yamlString) {
    // Replace literal \n with actual newlines in YAML string values
    // Pattern: looks for quoted strings containing \n
    def lines = yamlString.split('\n')
    def result = []
    
    lines.each { line ->
        // If line contains \n inside quotes, convert to literal block style
        if (line.contains('\\n') && (line.contains('"') || line.contains("'"))) {
            // Extract the key and value
            def matcher = line =~ /^(\s*)(\S+):\s*["'](.*)["']\s*$/
            if (matcher.matches()) {
                def indent = matcher[0][1]
                def key = matcher[0][2]
                def value = matcher[0][3]
                
                // Replace \n with actual newlines and use literal block style
                def lines_in_value = value.split('\\\\n')
                result << "${indent}${key}: |"
                lines_in_value.each { val ->
                    result << "${indent}  ${val}"
                }
            } else {
                result << line
            }
        } else {
            result << line
        }
    }
    
    return result.join('\n')
}

node("built-in") {
  
  stage("Convert YAML to Single Line Format") {
    try {
        def sshCommand = """
            ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | grep -v "\\-2" | cut -d"/" -f2'
        """
        def configmapList = sh(
            script: sshCommand, 
            returnStdout: true
        ).trim() 

        configmapList.split('\n').each { configM ->
            sshCommand = """
                ssh ${USER}@${SERVER} 'oc get configmap ${configM} -n ${NAMESPACE} -o yaml'
            """
            def yamlString = sh(
                script: sshCommand, 
                returnStdout: true
            ).trim()             
            def yaml = new Yaml().load(yamlString)
            
            if (yaml && yaml.data) {

                

                yaml.data.each { key, value ->
                    if (value instanceof String && value.contains('=')) {
                        // Trường hợp 2: XXX: A=B\nC=D
                        value.split('\n').findAll { line -> 
                            if (line.trim().startsWith('#')) return false
                            def parts = line.trim().split('=', 2)
                            if (parts.length < 2) return false
                            def keyPart = parts[0]
                            return keyPart.contains('PASS') || keyPart.contains('PWD') || 
                                   keyPart.contains('TOKEN') || keyPart.contains('SECRET') || 
                                   keyPart.contains('PRIVATE') || keyPart.contains('KEY')
                        }.each { line ->
                            def parts = line.trim().split('=', 2)
                            if (parts.length == 2 && parts[1].length() >= 7) {
                                passwordResults << "${configM}.${key}: ${parts[0]}=${parts[1]}"
                            }
                        }
                    } else {
                        // Trường hợp 1: A: B
                        if ((key.contains('PASS') || key.contains('PWD') || 
                             key.contains('TOKEN') || key.contains('SECRET') || 
                             key.contains('PRIVATE') || key.contains('KEY')) && 
                            value.length() >= 8) {
                            passwordResults << "${configM}: ${key}: ${value}"
                        }
                    }
                }
                
            }
        }
    } catch (Exception e) {
        echo "ERROR: ${e.message}"
    }
  }

  stage("Apply Masking and Generate ConfigMap Files") {
    try {
        def sshCommand = """
            ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | grep -v "\\-2" | cut -d"/" -f2'
        """
        def configmapList = sh(
            script: sshCommand, 
            returnStdout: true
        ).trim()

        configmapList.split('\n').each { configM ->
            echo "Processing ConfigMap: ${configM}"
            
            // Get the YAML for this ConfigMap
            sshCommand = """
                ssh ${USER}@${SERVER} 'oc get configmap ${configM} -n ${NAMESPACE} -o yaml'
            """
            def yamlString = sh(
                script: sshCommand, 
                returnStdout: true
            ).trim()
            
            def yaml = new Yaml().load(yamlString)
            
            if (yaml && yaml.data) {
                // Apply masking directly to the YAML data
                yaml.data.each { key, value ->
                    if (value instanceof String && value.contains('=')) {
                        // Trường hợp 2: XXX: A=B\nC=D (multi-line key=value pairs)
                        def lines = value.split('\n')
                        def modifiedLines = lines.collect { line ->
                            if (line.trim().startsWith('#') || !line.contains('=')) {
                                return line
                            }
                            
                            def parts = line.trim().split('=', 2)
                            if (parts.length == 2) {
                                def keyPart = parts[0]
                                def valuePart = parts[1]
                                
                                // Check if this key contains sensitive keywords
                                if (keyPart.contains('PASS') || keyPart.contains('PWD') || 
                                    keyPart.contains('TOKEN') || keyPart.contains('SECRET') || 
                                    keyPart.contains('PRIVATE') || keyPart.contains('KEY')) {
                                    
                                    def varName = "${configM}.${keyPart}".trim()
                                    
                                    // Mask if not in exclusion list
                                    if (!exclusionList.contains(varName) && valuePart.length() >= 7) {
                                        def leadingSpace = line.indexOf(keyPart) > 0 ? line.substring(0, line.indexOf(keyPart)) : ''
                                        return "${leadingSpace}${keyPart}=${'X' * valuePart.length()}"
                                    }
                                }
                            }
                            return line
                        }
                        yaml.data[key] = modifiedLines.join('\n')
                        
                    } else if (value instanceof String) {
                        // Trường hợp 1: A: B (simple key-value)
                        if ((key.contains('PASS') || key.contains('PWD') || 
                             key.contains('TOKEN') || key.contains('SECRET') || 
                             key.contains('PRIVATE') || key.contains('KEY')) && 
                            value.length() >= 8) {
                            
                            def varName = "${configM}.${key}".trim()
                            
                            // Mask if not in exclusion list
                            if (!exclusionList.contains(varName)) {
                                yaml.data[key] = 'X' * value.length()
                            }
                        }
                    }
                }
                
                // Clean up metadata fields
                if (yaml.metadata) {
                    yaml.metadata.remove('resourceVersion')
                    yaml.metadata.remove('uid')
                    yaml.metadata.remove('creationTimestamp')
                    yaml.metadata.remove('managedFields')
                    yaml.metadata.remove('selfLink')
                    yaml.metadata.remove('annotations')
                }
                
                // Remove status if present
                yaml.remove('status')
                
                // Generate file name
                def fileName = "${SERVER}-${configM}-${NAMESPACE}-${BUILD_NUMBER}-configmap.yaml"
                
                // Dump YAML with proper formatting
                def modifiedYamlString = dumpYaml(yaml)
                
                // Fix: Replace escaped \n with actual newlines
                // This handles the case where YAML dumper escapes newlines as \\n
                modifiedYamlString = fixNewlines(modifiedYamlString)
                
                // Write to file
                writeFile file: fileName, text: modifiedYamlString
                echo "Created masked ConfigMap file: ${fileName}"
            }
        }
        sh """
            scp *.yaml ${USER}@${SERVER}:/tmp/
        """        
    } catch (Exception e) {
        echo "ERROR in masking stage: ${e.message}"
    }
  }

    stage("Print Password Results") {
        if (passwordResults) {
            def grouped = [:]
            passwordResults.each { result ->
                def parts = result.split(': ', 2)
                def configName = parts[0].split('\\.')[0]
                def valueStr = parts[1]
                
                // Mask values based on exclusion list
                def maskedValueStr = valueStr
                if (valueStr.contains('=')) {
                    // Format: varName=varValue
                    def kvPair = valueStr.split('=', 2)
                    def varName = "${configName}.${kvPair[0]}".trim()
                    def varValue = kvPair[1]
                    
                    // Debug: print varName to check
                    echo "DEBUG - Checking varName: '${varName}' | In exclusion list: ${exclusionList.contains(varName)}"
                    
                    if (!exclusionList.contains(varName)) {
                        maskedValueStr = "${kvPair[0]}=${'X' * varValue.length()}"
                    }
                } else {
                    // Format: key: value
                    def keyValue = valueStr.split(': ', 2)
                    def varName = "${configName}.${keyValue[0]}".trim()
                    def varValue = keyValue[1]
                    
                    // Debug: print varName to check
                    echo "DEBUG - Checking varName: '${varName}' | In exclusion list: ${exclusionList.contains(varName)}"
                    
                    if (!exclusionList.contains(varName)) {
                        maskedValueStr = "${keyValue[0]}: ${'X' * varValue.length()}"
                    }
                }
                
                if (!grouped[configName]) grouped[configName] = []
                grouped[configName] << maskedValueStr
            }
            
            def yamlOutput = new StringBuilder("password_findings:\n")
            grouped.each { configM, values ->
                yamlOutput.append("  ${configM}:\n")
                values.each { value ->
                    yamlOutput.append("    - ${value}\n")
                }
            }
            echo yamlOutput.toString()
        } else {
            echo "password_findings: []"
        }
    }
    
    stage("Create Password Value YAML") {
        if (passwordResults) {
            def valueMap = [:]
            passwordResults.each { result ->
                def parts = result.split(': ', 2)
                def configName = parts[0].contains('.') ? parts[0].split('\\.')[0] : parts[0]
                def valueStr = parts[1]
                
                if (valueStr.contains('=')) {
                    // Trường hợp: configM.key: varName=varValue
                    def kvPair = valueStr.split('=', 2)
                    def varName = "${configName}.${kvPair[0]}".trim()
                    def varValue = kvPair[1]
                    
                    // Debug: print varName to check
                    echo "DEBUG - Checking varName: '${varName}' | In exclusion list: ${exclusionList.contains(varName)}"
                    
                    // Mask value if not in exclusion list
                    def finalValue = exclusionList.contains(varName) ? varValue : ('X' * varValue.length())
                    
                    if (!valueMap[finalValue]) valueMap[finalValue] = []
                    valueMap[finalValue] << varName
                } else {
                    // Trường hợp: configM: key: value
                    def keyValue = valueStr.split(': ', 2)
                    def varName = "${configName}.${keyValue[0]}".trim()
                    def varValue = keyValue[1]
                    
                    // Debug: print varName to check
                    echo "DEBUG - Checking varName: '${varName}' | In exclusion list: ${exclusionList.contains(varName)}"
                    
                    // Mask value if not in exclusion list
                    def finalValue = exclusionList.contains(varName) ? varValue : ('X' * varValue.length())
                    
                    if (!valueMap[finalValue]) valueMap[finalValue] = []
                    valueMap[finalValue] << varName
                }
            }
            
            def yamlContent = new StringBuilder("password_values:\n")
            valueMap.each { value, variables ->
                yamlContent.append("  \"${value}\":\n")
                variables.each { var ->
                    yamlContent.append("    - ${var}\n")
                }
            }
            print yamlContent.toString()
            writeFile file: 'password_values.yaml', text: yamlContent.toString()
            echo "Created password_values.yaml"
        }
    }

    stage("Gen Backup Configmap") {
        try {
            def sshCommand = """
                ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | grep -v "\\-2" | cut -d"/" -f2'
            """
            def configmapList = sh(
                script: sshCommand, 
                returnStdout: true
            ).trim()

            configmapList.split('\n').each { configM ->
                def fileName = "${SERVER}-${configM}-${NAMESPACE}-${BUILD_NUMBER}-configmap.yaml"
                echo "Backup file generated: ${fileName}"
                // Additional backup logic can be added here if needed
            }
        } catch (Exception e) {
            echo "ERROR in backup stage: ${e.message}"
        }
    }

    stage("Clean") {
        try {
            def sshCommand = """
                ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | grep -v "\\-2" | cut -d"/" -f2'
            """
            def configmapList = sh(
                script: sshCommand, 
                returnStdout: true
            ).trim()

            configmapList.split('\n').each { configM ->
                def fileName = "${SERVER}-${configM}-${NAMESPACE}-${BUILD_NUMBER}-configmap.yaml"
                echo "Cleaning up: ${fileName}"
                // Clean up logic using the new file name
                sh "rm -f ${fileName}"
            }
        } catch (Exception e) {
            echo "ERROR in clean stage: ${e.message}"
        }
    }

}
