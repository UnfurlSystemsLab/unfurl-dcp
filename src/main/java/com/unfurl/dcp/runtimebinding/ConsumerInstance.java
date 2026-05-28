package com.unfurl.dcp.runtimebinding;

import java.net.URI;

public record ConsumerInstance(URI componentUri, String componentVersion, String instanceName) {
}
