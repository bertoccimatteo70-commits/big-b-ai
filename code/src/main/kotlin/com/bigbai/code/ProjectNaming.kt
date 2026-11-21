package com.bigbai.code

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Naming and starter content for projects created by the app itself.
 *
 * The user should never have to invent a folder name or an entry file: "Nuovo
 * progetto" creates a real folder in the app's documents directory, names it
 * from the date and writes a working `index.html`. Pure functions so the rules
 * are unit-tested on the JVM.
 */
object ProjectNaming {

    /** Safe folder name: lowercase, digits and dashes only. */
    fun folderName(timestamp: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = zone
        return "progetto-${format.format(Date(timestamp))}"
    }

    /** Entry file created together with the project. */
    const val ENTRY_FILE = "index.html"

    fun starterHtml(title: String): String {
        val safeTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .ifBlank { "Nuovo progetto" }
        return """
            <!doctype html>
            <html lang="it">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$safeTitle</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  font-family: Georgia, 'Times New Roman', serif;
                  background: #f6f7fb;
                  color: #111827;
                }
                main {
                  max-width: 34rem;
                  padding: 2rem;
                  background: #ffffff;
                  border: 1px solid #d7dce6;
                  border-radius: 20px;
                }
                h1 { margin-top: 0; font-size: 1.6rem; }
                p { line-height: 1.6; }
                code { font-family: ui-monospace, Menlo, monospace; }
              </style>
            </head>
            <body>
              <main>
                <h1>$safeTitle</h1>
                <p>Questo progetto è stato creato da ITAGPT Code. Modifica
                  <code>$ENTRY_FILE</code> nell'editor e usa l'anteprima per vederlo.</p>
                <p>La sandbox non ha accesso alla rete se non lo autorizzi.</p>
              </main>
            </body>
            </html>
        """.trimIndent()
    }
}
