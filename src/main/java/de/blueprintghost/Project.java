package de.blueprintghost;

/** Ein gespeichertes Bauprojekt: welcher Bauplan, wo er steht, wie er gedreht ist. */
public final class Project {
	public String name;
	/** Absoluter Pfad zur Bauplan-Datei. */
	public String source;
	public int x, y, z;
	public String rotation = "NONE";
	public String mirror = "NONE";
	/** Manuell gewählte Ebene (-1 = keine). */
	public int manualLayer = -1;
	/** Zuletzt bekannter Fortschritt, nur zur Anzeige in der Liste. */
	public int done, total;

	public Project() {
	}

	public Project(String name, String source) {
		this.name = name;
		this.source = source;
	}

	public int percent() {
		return total == 0 ? 0 : (int) Math.round(100.0 * done / total);
	}
}
