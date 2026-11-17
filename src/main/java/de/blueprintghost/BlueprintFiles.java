package de.blueprintghost;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

/** Ordnerverwaltung und Download von Bauplänen. */
public final class BlueprintFiles {
	public static final long MAX_DOWNLOAD = 64L * 1024 * 1024;
	private static final String[] EXTENSIONS = {".litematic", ".schem", ".schematic", ".nbt"};

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	private BlueprintFiles() {
	}

	/** .minecraft/config/blueprintghost – hier eigene Baupläne ablegen. */
	public static Path folder() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("blueprintghost");
		try {
			Files.createDirectories(dir.resolve("downloads"));
		} catch (IOException ignored) {
		}
		return dir;
	}

	public static boolean isBlueprintFile(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (String ext : EXTENSIONS) {
			if (lower.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	/** Alle Baupläne im Ordner (inkl. downloads/), relativ benannt. */
	public static List<String> list() {
		Path dir = folder();
		List<String> result = new ArrayList<>();
		try (Stream<Path> files = Files.walk(dir, 2)) {
			files.filter(Files::isRegularFile)
				.filter(p -> isBlueprintFile(p.getFileName().toString()))
				.forEach(p -> result.add(dir.relativize(p).toString().replace('\\', '/')));
		} catch (IOException ignored) {
		}
		result.sort(String.CASE_INSENSITIVE_ORDER);
		return result;
	}

	/** Löst eine Eingabe (URL, Dateiname im Ordner oder absoluter Pfad) zu einer lokalen Datei auf. Blockiert! */
	public static Path resolve(String source) throws IOException, InterruptedException {
		source = source.trim();
		if (source.startsWith("\"") && source.endsWith("\"") && source.length() > 1) {
			source = source.substring(1, source.length() - 1);
		}
		String lower = source.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return download(source);
		}
		Path local = folder().resolve(source);
		if (!Files.isRegularFile(local)) {
			local = folder().resolve("downloads").resolve(source);
		}
		if (!Files.isRegularFile(local)) {
			local = Path.of(source);
		}
		if (!Files.isRegularFile(local)) {
			throw new IOException("Datei nicht gefunden: " + source);
		}
		return unpackIfZip(local);
	}

	/** Für das Wiederherstellen beim Start: nur lokale Dateien, nie Netzwerk. */
	public static Path resolveLocalOnly(String path) throws IOException {
		Path p = Path.of(path);
		if (!Files.isRegularFile(p)) {
			throw new IOException("Datei nicht gefunden: " + path);
		}
		return p;
	}

	private static Path download(String url) throws IOException, InterruptedException {
		url = normalizeUrl(url);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("User-Agent", "Mozilla/5.0 (BlueprintGhost Minecraft mod)")
			.GET()
			.build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() / 100 != 2) {
			response.body().close();
			throw new IOException("Download fehlgeschlagen: HTTP " + response.statusCode());
		}
		String contentType = response.headers().firstValue("Content-Type").orElse("");
		if (contentType.startsWith("text/html")) {
			response.body().close();
			throw new IOException("Der Link zeigt auf eine Webseite, nicht auf eine Datei. Nutze den direkten Download-Link (Rechtsklick auf 'Download' -> Link kopieren).");
		}

		String fileName = response.headers().firstValue("Content-Disposition")
			.map(BlueprintFiles::fileNameFromDisposition)
			.orElse(null);
		if (fileName == null) {
			String path = response.uri().getPath();
			fileName = path.substring(path.lastIndexOf('/') + 1);
			fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
		}
		fileName = sanitize(fileName);
		if (fileName.isEmpty()) {
			fileName = "download_" + System.currentTimeMillis() + ".schem";
		}

		Path target = folder().resolve("downloads").resolve(fileName);
		Path tmp = target.resolveSibling(fileName + ".part");
		try (InputStream in = response.body()) {
			long total = 0;
			byte[] buf = new byte[64 * 1024];
			try (var out = Files.newOutputStream(tmp)) {
				int read;
				while ((read = in.read(buf)) > 0) {
					total += read;
					if (total > MAX_DOWNLOAD) {
						throw new IOException("Datei ist größer als 64 MB.");
					}
					out.write(buf, 0, read);
				}
			}
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		return unpackIfZip(target);
	}

	/** Bekannte Hoster auf Direkt-Download umbiegen. */
	static String normalizeUrl(String url) {
		// GitHub: /blob/ -> raw
		if (url.contains("github.com/") && url.contains("/blob/")) {
			url = url.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/");
		}
		// Dropbox: dl=0 -> dl=1
		if (url.contains("dropbox.com/")) {
			if (url.contains("dl=0")) {
				url = url.replace("dl=0", "dl=1");
			} else if (!url.contains("dl=1")) {
				url += (url.contains("?") ? "&" : "?") + "dl=1";
			}
		}
		// Google Drive: /file/d/<id>/view -> uc?export=download&id=<id>
		var m = java.util.regex.Pattern.compile("drive\\.google\\.com/file/d/([^/]+)").matcher(url);
		if (m.find()) {
			url = "https://drive.google.com/uc?export=download&id=" + m.group(1);
		}
		return url;
	}

	private static String fileNameFromDisposition(String header) {
		var m = java.util.regex.Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(header);
		return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
	}

	private static String sanitize(String name) {
		name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		while (name.startsWith(".")) {
			name = name.substring(1);
		}
		return name.length() > 100 ? name.substring(name.length() - 100) : name;
	}

	/** Wenn die Datei ein ZIP ist (häufig bei Download-Seiten), die erste Bauplan-Datei daraus entpacken. */
	private static Path unpackIfZip(Path file) throws IOException {
		byte[] head = new byte[2];
		try (InputStream in = Files.newInputStream(file)) {
			if (in.read(head) < 2 || head[0] != 'P' || head[1] != 'K') {
				return file;
			}
		}
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String entryName = entry.getName();
				String base = entryName.substring(entryName.lastIndexOf('/') + 1);
				if (!isBlueprintFile(base)) {
					continue;
				}
				// Nur den Dateinamen verwenden -> kein Pfad aus dem Archiv (Zip-Slip ausgeschlossen)
				Path target = folder().resolve("downloads").resolve(sanitize(base));
				long total = 0;
				byte[] buf = new byte[64 * 1024];
				try (var out = Files.newOutputStream(target)) {
					int read;
					while ((read = zip.read(buf)) > 0) {
						total += read;
						if (total > MAX_DOWNLOAD) {
							throw new IOException("Datei im ZIP ist größer als 64 MB.");
						}
						out.write(buf, 0, read);
					}
				}
				return target;
			}
		}
		throw new IOException("Im ZIP-Archiv wurde keine .litematic/.schem/.nbt-Datei gefunden.");
	}
}
