// ==================== PASSWORD LIST CONFIGURATION ====================
// Add passwords to search for in Kubernetes ConfigMaps and Secrets
// This list will be used by scanK8sSecrets.groovy

def passwordsToFind = [
    // Add your target passwords here
    // Example:
    // 'mySecretPassword123',
    // 'anotherPassword456',
    // 'databasePass789'
]

return passwordsToFind
