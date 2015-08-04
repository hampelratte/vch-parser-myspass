package de.berlios.vch.parser.myspass;

import static de.berlios.vch.parser.myspass.MyspassParser.BASE_URI;
import static de.berlios.vch.parser.myspass.MyspassParser.CHARSET;

import java.io.StringReader;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.osgi.service.log.LogService;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.IVideoPage;

public class VideopageParser {

    private LogService logger;

    public VideopageParser(LogService logger) {
        this.logger = logger;
    }

    public IVideoPage parseVideoPage(IVideoPage vpage) throws Exception {
        String uri = vpage.getUri().toString();
        Matcher m = Pattern.compile("--/(\\d+)/").matcher(uri);
        if (m.find()) {
            String id = m.group(1);
            uri = BASE_URI + "/myspass/includes/apps/video/getvideometadataxml.php?id=" + id;
            String content = HttpUtils.get(uri, null, CHARSET);
            content = content.trim();
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content)));

            String episode = doc.getElementsByTagName("title").item(0).getTextContent();
            vpage.setTitle(episode);

            String description = doc.getElementsByTagName("description").item(0).getTextContent();
            vpage.setDescription(description);

            String preview = doc.getElementsByTagName("imagePreview").item(0).getTextContent();
            vpage.setThumbnail(new URI(preview));

            String videoUri = doc.getElementsByTagName("url_flv").item(0).getTextContent();
            vpage.setVideoUri(new URI(videoUri));

            int durationSeconds = parseDuration(doc);
            vpage.setDuration(durationSeconds);

            Calendar publishDate = parsePublishDate(doc);
            vpage.setPublishDate(publishDate);
            return vpage;
        } else {
            throw new Exception("No ID found in URI");
        }
    }

    private Calendar parsePublishDate(Document doc) {
        try {
            Calendar publishDate = Calendar.getInstance();

            // parse the date
            String date = doc.getElementsByTagName("broadcast_date").item(0).getTextContent();
            Date broadcastDate = new SimpleDateFormat("yyyy-MM-dd").parse(date);
            publishDate.setTime(broadcastDate);

            // parse the time
            String time = doc.getElementsByTagName("broadcast_time").item(0).getTextContent();
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            publishDate.set(Calendar.HOUR_OF_DAY, hour);
            int minute = Integer.parseInt(parts[1]);
            publishDate.set(Calendar.MINUTE, minute);
            publishDate.set(Calendar.SECOND, 0);

            return publishDate;
        } catch (Exception e) {
            logger.log(LogService.LOG_WARNING, "Couldn't parse publish date", e);
        }
        return null;
    }

    private int parseDuration(Document doc) {
        try {
            String duration = doc.getElementsByTagName("duration").item(0).getTextContent();
            String[] parts = duration.split(":");
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            int durationSeconds = minutes * 60 + seconds;
            return durationSeconds;
        } catch (Exception e) {
            logger.log(LogService.LOG_WARNING, "Couldn't parse duration", e);
            return -1;
        }
    }
}
