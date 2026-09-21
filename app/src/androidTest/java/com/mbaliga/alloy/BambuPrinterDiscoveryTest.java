package com.mbaliga.alloy;

import org.junit.Assert;
import org.junit.Test;

/** Parser and wire-contract coverage for the read-only local printer scan. */
public final class BambuPrinterDiscoveryTest {
    @Test public void buildsBambuCompatibleReadOnlySearch() {
        String request = BambuPrinterDiscovery.buildSearchRequest();
        Assert.assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"));
        Assert.assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"));
        Assert.assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"));
        Assert.assertTrue(request.contains("ST: " + BambuPrinterDiscovery.DEVICE_TYPE + "\r\n"));
        Assert.assertTrue(request.endsWith("\r\n\r\n"));
    }

    @Test public void buildsVendorPortSearchesWithoutAllowingArbitraryDestinations() {
        Assert.assertTrue(BambuPrinterDiscovery.buildSearchRequest(BambuPrinterDiscovery.BAMBU_SSDP_PORT)
                .contains("HOST: 239.255.255.250:1990\r\n"));
        Assert.assertTrue(BambuPrinterDiscovery.buildSearchRequest(BambuPrinterDiscovery.LISTEN_PORT)
                .contains("HOST: 239.255.255.250:2021\r\n"));
        try {
            BambuPrinterDiscovery.buildSearchRequest(8888);
            Assert.fail("arbitrary discovery destinations must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void parsesA1MiniAnnouncementAndUsesLocationHost() throws Exception {
        String response = "HTTP/1.1 200 OK\r\n"
                + "Location: 192.168.50.24\r\n"
                + "ST: urn:bambulab-com:device:3dprinter:1\r\n"
                + "USN: 01S00A123456789\r\n"
                + "DevModel.bambu.com: N1\r\n"
                + "DevName.bambu.com: Workshop A1 Mini\r\n"
                + "DevSignal.bambu.com: -48\r\n"
                + "DevConnect.bambu.com: lan\r\n"
                + "DevBind.bambu.com: free\r\n\r\n";

        BambuPrinterDiscovery.Printer printer = BambuPrinterDiscovery.parseResponse(response, "192.168.50.1");
        Assert.assertNotNull(printer);
        Assert.assertEquals("192.168.50.24", printer.host);
        Assert.assertEquals("01S00A123456789", printer.serial);
        Assert.assertEquals("N1", printer.model);
        Assert.assertEquals("Workshop A1 Mini", printer.name);
        Assert.assertTrue(printer.isA1Mini());
        Assert.assertTrue(printer.isLanMode());
        Assert.assertEquals("01S00A123456789@192.168.50.24", printer.identityKey());
    }

    @Test public void parsesUrlLocationAndDefaultsMissingName() throws Exception {
        String response = "HTTP/1.1 200 OK\n"
                + "Location: http://a1-mini.local:8080/announce\n"
                + "ST: urn:bambulab-com:device:3dprinter:1\n"
                + "USN: serial-123\n"
                + "DevModel.bambu.com: N1\n"
                + "DevConnect.bambu.com: lan\n\n";
        BambuPrinterDiscovery.Printer printer = BambuPrinterDiscovery.parseResponse(response, "192.168.1.8");
        Assert.assertNotNull(printer);
        Assert.assertEquals("a1-mini.local", printer.host);
        Assert.assertEquals("A1 Mini", printer.name);
    }

    @Test public void rejectsNonBambuOrMalformedAnnouncements() throws Exception {
        Assert.assertNull(BambuPrinterDiscovery.parseResponse(
                "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n\r\n", "192.168.1.8"));
        Assert.assertNull(BambuPrinterDiscovery.parseResponse(
                "HTTP/1.1 200 OK\r\nST: urn:bambulab-com:device:3dprinter:1\r\n"
                        + "USN: serial\r\nDevModel.bambu.com: N1\r\nLocation: bad host\r\n\r\n",
                "192.168.1.8"));
        Assert.assertNull(BambuPrinterDiscovery.parseResponse(
                "HTTP/1.1 404 Not Found\r\n\r\n", "192.168.1.8"));
    }
}
