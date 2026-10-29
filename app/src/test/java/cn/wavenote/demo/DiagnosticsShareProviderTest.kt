package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class DiagnosticsShareProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "wavenote-diagnostics-1793000000-01234567-89ab-cdef-0123-456789abcdef.jsonl"
    @Test fun onlyValidExistingExportsAreReadable() {
        val file = File(temporary.root, name).apply { writeText("{}\n") }
        assertEquals(file, DiagnosticsShareProvider.resolve(temporary.root, name))
        listOf("..", "../$name", "events-0.jsonl", "scope.json", "", "$name/extra").forEach {
            try { DiagnosticsShareProvider.resolve(temporary.root, it); fail("unexpected access") } catch (_: FileNotFoundException) { }
        }
        file.setLastModified(System.currentTimeMillis() - 86400_001L)
        try { DiagnosticsShareProvider.resolve(temporary.root, name); fail("expired") } catch (_: FileNotFoundException) { }
    }
    @Test fun symbolicLinksCannotEscapeExportDirectory() {
        val root = temporary.newFolder("exports"); val outside = temporary.newFile("private.json").apply { writeText("private") }
        Files.createSymbolicLink(File(root, name).toPath(), outside.toPath())
        try { DiagnosticsShareProvider.resolve(root, name); fail("symbolic link escaped") } catch (_: FileNotFoundException) { }
    }
    @Test fun providerRequiresExplicitUriGrantsAndNoStoragePermission() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        val nodes = document.getElementsByTagName("provider")
        val provider = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(namespace, "name") == ".DiagnosticsShareProvider" }
        assertEquals("false", provider.getAttributeNS(namespace, "exported"))
        assertEquals("true", provider.getAttributeNS(namespace, "grantUriPermissions"))
        val permissions = document.getElementsByTagName("uses-permission")
        assertFalse((0 until permissions.length).map { (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(namespace, "name") }
            .any { it.contains("EXTERNAL_STORAGE") || it.contains("MANAGE_EXTERNAL_STORAGE") })
    }
}
