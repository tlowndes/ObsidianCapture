package uk.tomlowndes.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Errand places by search (errand-places-v1). */
class PlaceSearchTest {
    private val photon = """{"type":"FeatureCollection","features":[
      {"geometry":{"coordinates":[-1.4510,53.8170],"type":"Point"},"properties":{"name":"B&Q Bradford","street":"Mayo Avenue","city":"Bradford","postcode":"BD5 8HZ"}},
      {"geometry":{"coordinates":[-1.5900,53.8050],"type":"Point"},"properties":{"name":"B&Q Kirkstall","street":"Kirkstall Road","district":"Kirkstall","city":"Leeds","postcode":"LS4 2AZ"}},
      {"geometry":{"coordinates":[-1.5900,53.8050],"type":"Point"},"properties":{"name":"B&Q Kirkstall","city":"Leeds"}},
      {"geometry":{"coordinates":[-1.55,53.80],"type":"Point"},"properties":{"street":"Briggate","housenumber":"12","city":"Leeds"}},
      {"properties":{"name":"No geometry"}}
    ]}"""

    @Test fun `photon results parse with a short address`() {
        val hits = PlaceSearch.parsePhoton(photon)
        assertEquals(4, hits.size)
        assertEquals("B&Q Kirkstall", hits[1].name)
        assertEquals("Kirkstall Road, Kirkstall, LS4 2AZ", hits[1].address)
        assertEquals(53.805, hits[1].lat, 1e-9)
        assertEquals(-1.59, hits[1].lon, 1e-9)
        assertEquals("12 Briggate", hits[3].name)
        assertEquals(emptyList<PlaceHit>(), PlaceSearch.parsePhoton("not json"))
    }

    @Test fun `nominatim fallback parses`() {
        val json = """[{"lat":"53.8050","lon":"-1.5900","name":"B&Q","display_name":"B&Q, Kirkstall Road, Leeds, West Yorkshire, England"}]"""
        val h = PlaceSearch.parseNominatim(json).single()
        assertEquals("B&Q", h.name)
        assertEquals("Kirkstall Road, Leeds, West Yorkshire", h.address)
    }

    @Test fun `nearest first, duplicates dropped, by name without a location`() {
        val hits = PlaceSearch.parsePhoton(photon)
        val leeds = 53.80 to -1.57
        val near = PlaceSearch.sorted(hits, leeds)
        assertEquals(3, near.size)
        assertEquals("12 Briggate", near[0].name)
        assertEquals("B&Q Kirkstall", near[1].name)
        assertTrue(near[0].metres!! < near[1].metres!!)
        assertEquals(listOf("12 Briggate", "B&Q Bradford", "B&Q Kirkstall"), PlaceSearch.sorted(hits, null).map { it.name })
    }

    @Test fun `distance labels`() {
        assertEquals("350 m", PlaceSearch.distance(347.0))
        assertEquals("1.2 km", PlaceSearch.distance(1204.0))
        assertEquals("14 km", PlaceSearch.distance(14_300.0))
    }

    @Test fun `tag guessed from the name, or a tag you already use`() {
        assertEquals("@b&q", PlaceSearch.tagFor("B&Q Kirkstall"))
        assertEquals("@tesco", PlaceSearch.tagFor("Tesco Extra"))
        assertEquals("@sainsburys", PlaceSearch.tagFor("Sainsbury's Local", listOf("@sainsburys")))
        assertEquals("@sainsbury's", PlaceSearch.tagFor("Sainsbury's Local"))
    }

    @Test fun `search urls carry the query and the nudge`() {
        assertEquals("https://photon.komoot.io/api/?limit=8&lang=en&q=b%26q&lat=53.80000&lon=-1.57000", PlaceSearch.photonUrl(" b&q ", 53.8 to -1.57))
        assertTrue(PlaceSearch.nominatimUrl("LS4 2AZ").endsWith("q=LS4+2AZ"))
    }
}
