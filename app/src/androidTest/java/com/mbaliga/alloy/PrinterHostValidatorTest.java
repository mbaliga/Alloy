package com.mbaliga.alloy;

import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for the exact host grammar used by LAN transport. */
public final class PrinterHostValidatorTest {
    @Test public void acceptsPrinterHostFormsAndNormalizesPresentationSyntax() {
        Assert.assertEquals("192.168.50.24", PrinterHostValidator.require(" 192.168.50.24 "));
        Assert.assertEquals("a1-mini.local", PrinterHostValidator.require("A1-MINI.LOCAL"));
        Assert.assertEquals("fe80::1", PrinterHostValidator.require("[FE80::1]"));
        Assert.assertEquals("fe80::1%wlan0", PrinterHostValidator.require("fe80::1%wlan0"));
    }

    @Test public void rejectsUrlsPortsPathsAndMalformedAddresses() {
        String[] invalid = {
                "https://192.168.50.24", "192.168.50.24:8883", "192.168.50.24/path",
                "192.168.50.999", "[192.168.50.24]", "fe80:::1", "fe80::zzzz",
                "printer name", "printer_name.local", "user@printer.local", ""
        };
        for (String value : invalid) {
            try {
                PrinterHostValidator.require(value);
                Assert.fail("host should be rejected: " + value);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test public void discoveryParserDropsHostileLocation() throws Exception {
        String response = "HTTP/1.1 200 OK\r\n"
                + "ST: urn:bambulab-com:device:3dprinter:1\r\n"
                + "USN: SERIAL-1\r\n"
                + "DevModel.bambu.com: N1\r\n"
                + "Location: http://192.168.50.24:not-a-port/path\r\n\r\n";
        Assert.assertNull(BambuPrinterDiscovery.parseResponse(response, "192.168.50.24"));
    }
}
