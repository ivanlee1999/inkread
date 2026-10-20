package me.ash.reader.infrastructure.rss

import android.content.Context
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.io.SyndFeedInput
import java.io.StringReader
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import okio.Buffer
import okio.IOException
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.mock

internal const val enclosureUrlString1: String = "https://example.com/enclosure.jpg"
internal const val enclosureUrlString2: String = "https://github.blog/wp-content/uploads/2024/03/github_copilot_header.png"
internal const val imageUrlString: String = "https://example.com/image.jpg"
internal const val enclosureHtmlCase1: String = """
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/>
    """
internal const val enclosureHtmlCase2: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/> 
    """
internal const val enclosureHtmlCase3: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString2" type="image/png"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase1: String = """
        <img src="$enclosureUrlString1"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase2: String = """
        <img src="$imageUrlString"/> 
        <img src="$enclosureUrlString1"/> 
        <img src="$enclosureUrlString1"/> 
    """

@RunWith(MockitoJUnitRunner::class)
class RssHelperTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockIODispatcher: CoroutineDispatcher

    @Mock
    private lateinit var mockOkHttpClient: OkHttpClient

    private lateinit var rssHelper: RssHelper

    @Before
    fun setUp() {
        mockContext = mock<Context> { }
        mockIODispatcher = mock<CoroutineDispatcher> {}
        mockOkHttpClient = mock<OkHttpClient> {}
        rssHelper = RssHelper(mockContext, mockIODispatcher, mockOkHttpClient)
    }

    @Test
    fun testFindThumbnail() {
        Assert.assertNull(rssHelper.findThumbnail(""))
        Assert.assertNull(rssHelper.findThumbnail(" "))
        Assert.assertNull(rssHelper.findThumbnail(null))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase1))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase2))
        Assert.assertEquals(enclosureUrlString2, rssHelper.findThumbnail(enclosureHtmlCase3))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(imageHtmlCase1))
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(imageHtmlCase2))
    }

    @Test
    fun testEnclosureNoFilenameExtension() {
        val case = """
            <enclosure url="$imageUrlString" type="image/jpeg" length="0"/>
        """
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    @Test
    fun testMediaNamespaceThumbnailInRSS20() {
        val entry = rssEntry("<media:thumbnail url='$imageUrlString'/>")
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testMediaContentImageWithoutThumbnail() {
        val entry = rssEntry("<media:content url='$imageUrlString' medium='image' type='image/jpeg'/>")
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testGroupedMediaContentImageWhenTopLevelContentsAreEmpty() {
        val entry = rssEntry("""
            <media:group>
                <media:content url='$imageUrlString' medium='image' type='image/jpeg'/>
            </media:group>
        """)
        val module = entry.getModule(MediaModule.URI) as MediaEntryModule
        Assert.assertEquals(0, module.mediaContents.size)
        Assert.assertEquals(1, module.mediaGroups.single().contents.size)
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testGroupedMediaContentThumbnailHasPriorityOverVideoContent() {
        val entry = rssEntry("""
            <media:group>
                <media:content url='https://example.com/video.mp4' medium='video' type='video/mp4'>
                    <media:thumbnail url='$imageUrlString'/>
                </media:content>
            </media:group>
        """)
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testAudioEnclosureDoesNotHideMediaThumbnail() {
        val entry = rssEntry("""
            <enclosure url='https://example.com/episode.mp3' type='audio/mpeg' length='100'/>
            <media:thumbnail url='$imageUrlString'/>
        """)
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testImageEnclosureAfterAudioEnclosure() {
        val entry = rssEntry("""
            <enclosure url='https://example.com/episode.mp3' type='audio/mpeg' length='100'/>
            <enclosure url='$imageUrlString' type='image/jpeg' length='100'/>
        """)
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test fun testContentWithoutImageFallsBackToDescription() {
        val entry = rssEntry("""
            <content:encoded><![CDATA[<p>Full text with no photograph</p>]]></content:encoded>
            <description><![CDATA[<img src='/description.jpg'>]]></description>
        """)
        Assert.assertEquals("https://example.com/description.jpg", rssHelper.findThumbnail(entry))
    }

    @Test fun testContentPhotographHasPriorityOverDescription() {
        val entry = rssEntry("""
            <content:encoded><![CDATA[<img data-src='/content.jpg'>]]></content:encoded>
            <description><![CDATA[<img src='/description.jpg'>]]></description>
        """)
        Assert.assertEquals("https://example.com/content.jpg", rssHelper.findThumbnail(entry))
    }

    @Test fun testInvalidMediaUrlFallsBackWithoutLiteralNullImage() {
        val entry = rssEntry("""
            <media:content url='null' medium='image' type='image/jpeg'/>
            <description><![CDATA[<img src='/fallback.jpg'>]]></description>
        """)
        Assert.assertEquals("https://example.com/fallback.jpg", rssHelper.findThumbnail(entry))
    }

    private fun rssEntry(body: String): SyndEntry = SyndFeedInput().build(StringReader("""
        <rss version='2.0' xmlns:media='http://search.yahoo.com/mrss/' xmlns:content='http://purl.org/rss/1.0/modules/content/'>
          <channel><title>Fixture</title><link>https://example.com</link><description>Fixture feed</description>
            <item><title>Story</title><link>https://example.com/news/story</link>$body</item>
          </channel>
        </rss>
    """)).entries.single()

    @Test
    fun testReadStringLimitedWithinLimit() {
        val source = Buffer().writeString("hello", Charsets.UTF_8)

        Assert.assertEquals("hello", source.readStringLimited(Charsets.UTF_8, maxBytes = 5))
    }

    @Test
    fun testReadStringLimitedThrowsWhenOverLimit() {
        val source = Buffer().writeString("hello!", Charsets.UTF_8)

        Assert.assertThrows(IOException::class.java) {
            source.readStringLimited(Charsets.UTF_8, maxBytes = 5)
        }
    }

    @Test
    fun testReadByteArrayLimitedThrowsWhenOverLimit() {
        val source = Buffer().write(ByteArray(6))

        Assert.assertThrows(IOException::class.java) {
            source.readByteArrayLimited(maxBytes = 5)
        }
    }
}
