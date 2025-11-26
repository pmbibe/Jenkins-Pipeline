import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

def USER = ''
def DC_SERVER = ''
def DR_SERVER = ''
def NAMESPACES = ['','','']

def flattenYamlMap(map, prefix = '') {
    def result = []
    map.each { key, value ->
        def newKey = prefix ? "${prefix}.${key}" : key
        if (value instanceof Map) {
            result.addAll(flattenYamlMap(value, newKey))
        } else if (value instanceof List) {
            value.eachWithIndex { item, index ->
                def indexKey = "${newKey}[${index}]"
                if (item instanceof Map) {
                    result.addAll(flattenYamlMap(item, indexKey))
                } else if (item instanceof List) {
                    flattenListToProperties(item, indexKey, result)
                } else {
                    result << "${indexKey}=${item}"
                }
            }
        } else {
            result << "${newKey}=${value}"
        }
    }
    
    return result
}

def flattenListToProperties(list, prefix, result) {
    list.eachWithIndex { item, index ->
        def indexKey = "${prefix}[${index}]"
        
        if (item instanceof Map) {
            result.addAll(flattenYamlMap(item, indexKey))
        } else if (item instanceof List) {
            flattenListToProperties(item, indexKey, result)
        } else {
            result << "${indexKey}=${item}"
        }
    }
}



def generateConfigMapParameters(NAMESPACES, DC_SERVER, DR_SERVER) {
    def configParams = []
    def choices = []
    choices.add("DC: ${DC_SERVER}")
    choices.add("DR: ${DR_SERVER}")
    configParams.add(
        choice(
            name: 'SERVER',
            choices: choices,
            description: 'Chọn site'
        )
    )
    configParams.add(
        choice(
            name: 'NAMESPACE',
            choices: NAMESPACES,
            description: 'Chọn namespace'
        )
    )    
    
    return configParams
}

properties([
    parameters(generateConfigMapParameters(NAMESPACES, DC_SERVER, DR_SERVER))
])

node("built-in") {
  
  stage("Convert YAML to Single Line Format") {
    try {
        SERVER = SERVER.split(":")[1].trim()
        def sshCommand = """
            ssh ${USER}@${SERVER} 'oc get configmap -n ${NAMESPACE} -o name | cut -d"/" -f2'
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
            
            if (yaml && yaml.data && yaml.data["application.yml"]) {
                def yaml_application = new Yaml().load(yaml.data["application.yml"])
                def flattenedProperties = flattenYamlMap(yaml_application)
                def filename = "${configM}.properties"
                writeFile file: filename, text: flattenedProperties.join('\n')
                archiveArtifacts artifacts: filename
                echo "Saved ConfigMap ${configM} as artifact: ${filename}"
                
            }
        }
    } catch (Exception e) {
        echo "ERROR: ${e.message}"
    }
  }
}
