package de.berlios.vch.parser.myspass;

import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.json.JSONObject;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.osgi.service.log.LogService;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.HtmlParserUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;

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
        IOverviewPage root = new OverviewPage();
        root.setUri(new URI("vchpage://localhost/" + getId()));
        root.setTitle(getTitle());
        root.setParser(ID);

        try {
            String content = HttpUtils.get(BASE_URI + "/sendungen-a-bis-z/", HttpUtils.createFirefoxHeader(), CHARSET);
            Elements sections = HtmlParserUtils.getTags(content, "div[class~=category]");
            for (Element section : sections) {
                String sectionHtml = section.html();
                OverviewPage letterPage = new OverviewPage();
                letterPage.setParser(getId());
                letterPage.setTitle(section.id());
                letterPage.setUri(new URI("myspass://letter/"+ section.id()));
                Elements links = HtmlParserUtils.getTags(sectionHtml, "div[class~=category__item] a");
                for (Element link : links) {
                    Element thumb = link.getElementsByTag("img").get(0);
                    IOverviewPage programPage = new OverviewPage();
                    programPage.setParser(getId());
                    programPage.setTitle(thumb.attr("alt"));
                    programPage.setUri(new URI(BASE_URI + link.attr("href")));
                    letterPage.getPages().add(programPage);
                    logger.log(LogService.LOG_DEBUG, "Added " + link.text() + " at " + link.attr("href"));
                }

                if(!letterPage.getPages().isEmpty()) {
                    root.getPages().add(letterPage);
                }
            }
        } catch (Exception e) {
            logger.log(LogService.LOG_ERROR, "Couldn't parse overview page", e);
        }

        //Collections.sort(root.getPages(), new WebPageTitleComparator());
        return root;
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IOverviewPage) {
            IOverviewPage opage = (IOverviewPage) page;
            String uri = opage.getUri().toString();
            if(uri.startsWith("myspass://letter/") || uri.startsWith("myspass://season/")) {
                return page;
            } else if (uri.contains("tvshows") || uri.contains("webshows")) {
                return parseShowPage(opage);
            }

        } else if (page instanceof IVideoPage) {
            IVideoPage vpage = (IVideoPage) page;
            return new VideopageParser(logger).parseVideoPage(vpage);
        }

        throw new Exception("Not yet implemented!");
    }

    private IOverviewPage parseShowPage(IOverviewPage opage) throws Exception {
        String uri = opage.getUri().toString();
        String content = HttpUtils.get(uri, HttpUtils.createFirefoxHeader(), CHARSET);

        Elements sections = HtmlParserUtils.getTags(content, "div[class~=has-season-selector]");
        for (Element section : sections) {
            if(section.id().endsWith("_full_episode") || section.id().endsWith("_clip")) {
                String sectionHtml = section.html();
                try {
                    Element seasonSelector = HtmlParserUtils.getTag(sectionHtml, "select");
                    String seasonUriBase = BASE_URI + seasonSelector.attr("data-remote-endpoint");
                    Elements seasonSelectorOptions = HtmlParserUtils.getTags(sectionHtml, "select option");
                    String[] seasonUris = new String[seasonSelectorOptions.size()];
                    String[] seasonNames = new String[seasonSelectorOptions.size()];
                    for (int i = 0; i < seasonSelectorOptions.size(); i++) {
                        Element option = seasonSelectorOptions.get(i);
                        seasonNames[i] = option.text().trim();
                        seasonUris[i] = seasonUriBase + option.attr("data-remote-args");
                    }

                    OverviewPage sectionPage = new OverviewPage();
                    sectionPage.setParser(getId());
                    sectionPage.setTitle(section.id().endsWith("_clip") ? "Clips" : "Ganze Folgen");
                    sectionPage.setUri(new URI("myspass://season/"+URLEncoder.encode(sectionPage.getTitle(), CHARSET)));
                    opage.getPages().add(sectionPage);

                    for (int i = 0; i < seasonNames.length; i++) {
                        OverviewPage seasonPage = new OverviewPage();
                        seasonPage.setParser(getId());
                        seasonPage.setTitle(seasonNames[i]);
                        seasonPage.setUri(new URI("myspass://season/"+URLEncoder.encode(seasonNames[i], CHARSET)));
                        sectionPage.getPages().add(seasonPage);

                        // parse videos
                        String seasonJson = HttpUtils.get(seasonUris[i], null, CHARSET);
                        JSONObject json = new JSONObject(seasonJson);
                        String seasonContent = json.getString("slider");
                        Elements items = HtmlParserUtils.getTags(seasonContent, "div[class~=bacs-item]");
                        for (Element item : items) {
                            String itemHtml = item.html();
                            VideoPage video = new VideoPage();
                            video.setParser(getId());
                            Element thumb = HtmlParserUtils.getTag(itemHtml, "a > img");
                            video.setTitle(thumb.attr("alt"));
                            String href = thumb.parent().attr("href");
                            video.setUri(new URI(BASE_URI + href));
                            seasonPage.getPages().add(video);
                        }
                    }
                } catch(RuntimeException e) {
                    if(e.getMessage().contains("No element selected")) {
                        // ignore
                    } else {
                        throw e;
                    }
                }
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
