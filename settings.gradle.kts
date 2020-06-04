import java.net.URI

rootProject.name = "base"

sourceControl {
    gitRepository(URI("https://github.com/chaosite/sd-primitive-storage-layer.git")) {
        producesModule("il.ac.technion.cs.softwaredesign:primitive-storage-layer")
    }
}

include("library")
include("coursetorrent-app")
include("coursetorrent-test")