import CoreMediaIO
import Foundation

let providerSource = ConduitProviderSource(clientQueue: nil)
CMIOExtensionProvider.startService(provider: providerSource.provider)
CFRunLoopRun()
