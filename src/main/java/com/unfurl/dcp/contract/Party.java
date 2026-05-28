package com.unfurl.dcp.contract;

import java.net.URI;

public record Party(URI claimUri, String claimVersion) {
}
