package de.berlios.vch.parser.myspass;

import java.io.StringReader;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.osgi.service.log.LogService;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.HtmlParserUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.parser.WebPageTitleComparator;

@Component
@Provides
public class MyspassParser implements IWebParser {

    final static String CHARSET = "utf-8";

    final static String BASE_URI = "http://www.myspass.de";

    public static final String ID = MyspassParser.class.getName();

    private static final Map<String, String> HTTP_HEADER = new HashMap<String, String>();
    static {
        HTTP_HEADER.put("X-Requested-With", "XMLHttpRequest");
    }

    @Requires
    private LogService logger;

    @Override
    public IOverviewPage getRoot() throws Exception {
        IOverviewPage overview = new OverviewPage();
        overview.setUri(new URI("vchpage://localhost/" + getId()));
        overview.setTitle(getTitle());
        overview.setParser(ID);

        try {
            String content = HttpUtils.get(BASE_URI + "/myspass/ganze-folgen/", null, CHARSET);
            Elements categories = HtmlParserUtils.getTags(content, "ul[class~=showsAZ-container]");

            for (int i = 1; i < categories.size(); i++) { // start at 1, skipt the "Top" categorie
                Elements shows = categories.get(i).select("a.showsAZName");
                for (Element link : shows) {
                    IOverviewPage programPage = new OverviewPage();
                    programPage.setParser(getId());
                    programPage.setTitle(link.text());
                    programPage.setUri(new URI(BASE_URI + link.attr("href")));
                    overview.getPages().add(programPage);
                    logger.log(LogService.LOG_DEBUG, "Added " + link.text() + " at " + link.attr("href"));
                }
            }
        } catch (Exception e) {
            logger.log(LogService.LOG_ERROR, "Couldn't parse overview page", e);
        }

        Collections.sort(overview.getPages(), new WebPageTitleComparator());
        return overview;
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IOverviewPage) {
            IOverviewPage opage = (IOverviewPage) page;
            String uri = opage.getUri().toString();
            if (uri.contains("tvshows") || uri.contains("webshows")) {
                return parseShowPage(opage);
            } else if (uri.contains("getEpisodeListFromSeason")) {
                return parseSeasonPage(opage);
            }

        } else if (page instanceof IVideoPage) {
            IVideoPage vpage = (IVideoPage) page;
            return parseVideoPage(vpage);
        }

        throw new Exception("Not yet implemented!");
    }

    private IVideoPage parseVideoPage(IVideoPage vpage) throws Exception {
        String uri = vpage.getUri().toString();
        Matcher m = Pattern.compile("--/(\\d+)/").matcher(uri);
        if (m.find()) {
            String id = m.group(1);
            uri = BASE_URI + "/myspass/includes/apps/video/getvideometadataxml.php?id=" + id;
            String content = HttpUtils.get(uri, null, CHARSET);
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content)));
            // String program = doc.getElementsByTagName("format").item(0).getTextContent();
            String episode = doc.getElementsByTagName("title").item(0).getTextContent();
            String description = doc.getElementsByTagName("description").item(0).getTextContent();
            String duration = doc.getElementsByTagName("duration").item(0).getTextContent();
            String[] parts = duration.split(":");
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            int durationSeconds = minutes * 60 + seconds;
            String preview = doc.getElementsByTagName("imagePreview").item(0).getTextContent();
            String videoUri = doc.getElementsByTagName("url_flv").item(0).getTextContent();

            vpage.setTitle(episode);
            vpage.setDescription(description);
            vpage.setVideoUri(new URI(videoUri));
            vpage.setThumbnail(new URI(preview));
            vpage.setDuration(durationSeconds);
            return vpage;
        } else {
            throw new Exception("No ID found in URI");
        }
    }

    private IWebPage parseSeasonPage(IOverviewPage opage) throws Exception {
        String uri = opage.getUri().toString();
        String content = HttpUtils.get(uri, null, CHARSET);

        // if the season page is not empty, this season is paginated
        if (!opage.getPages().isEmpty()) {
            return opage;
        }

        // parse the episode page for episodes
        Elements episodes = HtmlParserUtils.getTags(content, "tr.episodeListInformation td.title a");
        for (Element a : episodes) {
            String path = a.attr("href");
            String episodeUri = BASE_URI + path;
            String title = a.text();
            IVideoPage vpage = new VideoPage();
            vpage.setParser(getId());
            vpage.setTitle(title);
            vpage.setUri(new URI(episodeUri));
            opage.getPages().add(vpage);
        }
        return opage;
    }

    private IOverviewPage parseShowPage(IOverviewPage opage) throws Exception {
        String uri = opage.getUri().toString();
        String content = HttpUtils.get(uri, null, CHARSET);

        Element a = HtmlParserUtils.getTag(content, "th.season_episode a");
        if (a != null) {
            content = HttpUtils.get(uri, HTTP_HEADER, CHARSET);
        } else {
            // simple site, no further parsing necessary
        }
        Elements sections = HtmlParserUtils.getTags(content, "ul.episodeListSeasonList");
        if (sections.size() > 0) {
            Elements seasons = sections.get(0).select("li[data-query]");
            for (int i = 0; i < seasons.size() - 2; i++) {
                Element li = seasons.get(i);
                IOverviewPage seasonPage = new OverviewPage();
                seasonPage.setParser(getId());
                seasonPage.setTitle(li.select("a").text());

                String action = li.attr("data-query");
                String seasonUri = BASE_URI + "/myspass/includes/php/ajax.php?v=2&ajax=true&action=" + action;
                seasonUri += "&category=full_episode&id=&sortBy=episode_asc&pageNumber=0";
                seasonPage.setUri(new URI(seasonUri));
                opage.getPages().add(seasonPage);

                String maxpages = li.attr("data-maxpages");
                if (!maxpages.isEmpty()) {
                    int pageCount = Integer.parseInt(maxpages);
                    for (int j = 0; j <= pageCount; j++) {
                        IOverviewPage page = new OverviewPage();
                        page.setParser(getId());
                        page.setTitle("Seite " + (j + 1));
                        uri = seasonUri + "&pageNumber=" + j;
                        page.setUri(new URI(uri));
                        seasonPage.getPages().add(page);
                    }
                }
                logger.log(LogService.LOG_DEBUG, "Added " + li.text() + " at " + seasonPage.getUri());
            }
        }

        return opage;
    }

    @Override
    public String getTitle() {
        return "MySpass";
    }

    @Override
    public String getId() {
        return ID;
    }
}
