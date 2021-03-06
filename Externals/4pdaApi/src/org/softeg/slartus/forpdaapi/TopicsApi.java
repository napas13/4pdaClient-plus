package org.softeg.slartus.forpdaapi;

import android.net.Uri;
import android.text.Html;
import android.text.TextUtils;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.softeg.slartus.forpdacommon.Functions;
import org.softeg.slartus.forpdacommon.NotReportException;
import org.softeg.slartus.forpdacommon.PatternExtensions;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Created by slinkin on 20.02.14.
 */
public class TopicsApi {
    public static Topics getFavoritesThemes(IHttpClient httpClient,
                                            OnProgressChangedListener progressChangedListener,
                                            int start, Boolean needThemesCount) throws IOException {
        String pageBody = httpClient.performGetWithCheckLogin("http://4pda.ru/forum/index.php?autocom=favtopics&st=" + start, progressChangedListener, progressChangedListener);
        Topics themes = new Topics();

        if (needThemesCount) {
            Matcher m = PatternExtensions.compile("<a href=\"http://4pda.ru/forum/index.php\\?autocom=.*?st=(\\d+)\">&raquo;</a>").matcher(pageBody);
            if (m.find()) {
                themes.setThemesCountInt(Integer.parseInt(m.group(1)) + 1);
            }
        }

        //Matcher rowMatcher = Pattern.compile("<!-- Begin Topic Entry \\d+ -->(.*?)<!-- End Topic Entry \\d+ -->").matcher(pageBody);
        Matcher m = PatternExtensions.compile("<!-- Begin Topic Entry \\d+ -->([\\s\\S]*?)<a id=\"tid-link-\\d+\" href=\"http://4pda.ru/forum/index.php\\?showtopic=(\\d+)\" title=\"[^\"'<>]*\">([^<>]*)</a></span>[\\s\\S]*?id='tid-desc-\\d+'>([^<>]*)</span>[\\s\\S]*?<span class=\"lastaction\">([^<>]*)<br /><a href=\"http://4pda.ru/forum/index.php\\?showtopic=\\d+&amp;view=getlastpost\">Послед.:</a> <b><a href='http://4pda.ru/forum/index.php\\?showuser=\\d+'>([^<>]*)</a>")
                .matcher(pageBody);

        String today = Functions.getToday();
        String yesterday = Functions.getYesterToday();
        int sortOrder = 1000 + start + 1;
        while (m.find()) {
            Topic topic = new Topic(m.group(2), m.group(3));
            if (m.group(1) != null)
                topic.setIsNew(m.group(1).contains("view=getnewpost"));
            topic.setDescription(m.group(4));
            topic.setLastMessageDate(Functions.parseForumDateTime(m.group(5), today, yesterday));
            topic.setLastMessageAuthor(m.group(6));
            topic.setSortOrder(Integer.toString(sortOrder++));
            themes.add(topic);
        }
        return themes;
    }

    public static ArrayList<FavTopic> getFavTopics(IHttpClient client,
                                                   ListInfo listInfo) throws ParseException, IOException, URISyntaxException {
        return getFavTopics(client, null, null, null, null, false, false, listInfo);
    }

    public static ArrayList<FavTopic> getFavTopics(IHttpClient client,
                                                   String sortKey,
                                                   String sortBy,
                                                   String pruneDay,
                                                   String topicFilter,
                                                   Boolean unreadInTop,
                                                   Boolean fullPagesList,
                                                   ListInfo listInfo) throws ParseException, IOException, URISyntaxException {
        List<NameValuePair> qparams = new ArrayList<>();
        qparams.add(new BasicNameValuePair("act", "fav"));
        qparams.add(new BasicNameValuePair("type", "topics"));
        if (sortKey != null)
            qparams.add(new BasicNameValuePair("sort_key", sortKey));
        if (sortBy != null)
            qparams.add(new BasicNameValuePair("sort_by", sortBy));
        if (pruneDay != null)
            qparams.add(new BasicNameValuePair("prune_day", pruneDay));
        if (topicFilter != null)
            qparams.add(new BasicNameValuePair("topicfilter", topicFilter));
        qparams.add(new BasicNameValuePair("st", Integer.toString(listInfo.getFrom())));


        URI uri = URIUtils.createURI("http", "4pda.ru", -1, "/forum/index.php",
                URLEncodedUtils.format(qparams, "UTF-8"), null);
        String pageBody = client.performGet(uri.toString());

        Document doc = Jsoup.parse(pageBody);

        Matcher m = PatternExtensions.compile("<a href=\"/forum/index.php\\?act=[^\"]*?st=(\\d+)\">&raquo;</a>").matcher(pageBody);
        if (m.find()) {
            listInfo.setOutCount(Integer.parseInt(m.group(1)) + 1);
        }

        Pattern lastPostPattern = Pattern.compile("<a href=\"[^\"]*view=getlastpost[^\"]*\">Послед.:</a>\\s*<a href=\"/forum/index.php\\?showuser=\\d+\">(.*?)</a>(.*?)$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Pattern trackTypePattern = Pattern.compile("wr_fav_subscribe\\(\\d+,\"(\\w+)\"\\);",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        ArrayList<FavTopic> res = new ArrayList<>();
        String today = Functions.getToday();
        String yesterday = Functions.getYesterToday();
        int sortOrder = 1000 + listInfo.getFrom() + 1;
        for (Element topicElement : doc.select("div[data-item-fid]")) {
            Elements elements = topicElement.select("div.topic_title");
            if (elements.size() == 0) continue;
            Element topicTitleDivElement = elements.first();
            elements = topicTitleDivElement.select("a");
            if (elements.size() == 0) continue;
            Element element = elements.first();
            Uri ur = Uri.parse(element.attr("href"));
            String tId = topicElement.attr("data-item-fid");
            Boolean pinned = "1".equals(topicElement.attr("data-item-pin"));
            String trackType = null;
            elements = topicElement.select("div.topic_body");
            if (elements.size() > 0) {
                String html = elements.first().html();
                m = trackTypePattern.matcher(html);
                if (m.find())
                    trackType = m.group(1);
            }

            if (TextUtils.isEmpty(ur.getQueryParameter("showtopic"))) {
                FavTopic topic = new FavTopic(null, topicTitleDivElement.text());
                topic.setTid(tId);
                topic.setPinned(pinned);
                topic.setTrackType(trackType);
                topic.setDescription("Форум");
                topic.setSortOrder(Integer.toString(sortOrder++));
                res.add(topic);
                continue;
            }


            String id = ur.getQueryParameter("showtopic");
            String title = element.text();
            FavTopic topic = new FavTopic(id, title);
            topic.setTid(tId);
            topic.setPinned(pinned);
            topic.setTrackType(trackType);
            elements = topicElement.select("div.topic_body");
            if (elements.size() > 0) {
                Element topicBodyDivElement = elements.first();
                elements = topicBodyDivElement.select("span.topic_desc");
                if (elements.size() > 0)
                    topic.setDescription(elements.first().text());
                String text = topicBodyDivElement.html();
                topic.setIsNew(text.contains("view=getnewpost"));

                m = lastPostPattern.matcher(text);
                if (m.find()) {
                    topic.setLastMessageDate(Functions.parseForumDateTime(m.group(2), today, yesterday));
                    topic.setLastMessageAuthor(m.group(1));
                }
                topic.setSortOrder(Integer.toString(sortOrder++));
                res.add(topic);
            }
        }

        if (fullPagesList) {
            while (true) {
                if (listInfo.getOutCount() <= res.size())
                    break;
                listInfo.setFrom(res.size());
                ArrayList<FavTopic> nextPageTopics = getFavTopics(client, sortKey, sortBy, pruneDay, topicFilter, false, false, listInfo);
                if (nextPageTopics.size() == 0)
                    break;
                res.addAll(nextPageTopics);
            }
        }
        if (unreadInTop) {
            final int asc = -1;// новые вверху
            Collections.sort(res, new Comparator<Topic>() {
                @Override
                public int compare(Topic topic, Topic topic2) {
                    if (topic.getState() == topic2.getState())
                        return 0;
                    return (topic.getState() == Topic.FLAG_NEW) ? asc : (-asc);

                }
            });
        }
        if (res.size() == 0) {
            m = PatternExtensions.compile("<div class=\"errorwrap\">([\\s\\S]*?)</div>")
                    .matcher(pageBody);
            if (m.find()) {
                throw new NotReportException(Html.fromHtml(m.group(1)).toString(), new Exception(Html.fromHtml(m.group(1)).toString()));
            }
        }
        return res;
    }


    public static ArrayList<Topic> getForumTopics(IHttpClient client,
                                                  String forumId, String sortKey, String sortBy, String pruneDay, String topicFilter,
                                                  Boolean unreadInTop,
                                                  ListInfo listInfo) throws ParseException, IOException, URISyntaxException {
        List<NameValuePair> qparams = new ArrayList<>();
        qparams.add(new BasicNameValuePair("showforum", forumId));
        qparams.add(new BasicNameValuePair("sort_key", sortKey));
        qparams.add(new BasicNameValuePair("sort_by", sortBy));
        qparams.add(new BasicNameValuePair("prune_day", pruneDay));
        qparams.add(new BasicNameValuePair("topicfilter", topicFilter));
        qparams.add(new BasicNameValuePair("st", Integer.toString(listInfo.getFrom())));


        URI uri = URIUtils.createURI("http", "4pda.ru", -1, "/forum/index.php",
                URLEncodedUtils.format(qparams, "UTF-8"), null);
        String pageBody = client.performGet(uri.toString());


        int start = listInfo.getFrom();
        Pattern lastPageStartPattern = Pattern.compile("<a href=\"(http://4pda.ru)?/forum/index.php\\?showforum=\\d+&amp;[^\"]*?st=(\\d+)\">", Pattern.CASE_INSENSITIVE);

        Pattern themesPattern = Pattern.compile("<div class=\"topic_title\">.*?<a href=\"/forum/index.php\\?showtopic=(\\d+)\">([^<]*)</a>.*?</div><div class=\"topic_body\"><span class=\"topic_desc\">([^<]*)<br /></span><span class=\"topic_desc\">автор: <a href=\"/forum/index.php\\?showuser=\\d+\">[^<]*</a></span><br />(<a href=\"/forum/index.php\\?showtopic=\\d+&amp;view=getnewpost\">Новые</a>)?\\s*<a href=\"/forum/index.php\\?showtopic=\\d+&amp;view=getlastpost\">Послед.:</a> <a href=\"/forum/index.php\\?showuser=(\\d+)\">([^<]*)</a>(.*?)<.*?/div>", Pattern.CASE_INSENSITIVE);

        String today = Functions.getToday();
        String yesterday = Functions.getYesterToday();
        ArrayList<Topic> res = new ArrayList<>();
        Matcher m = themesPattern.matcher(pageBody);
        int sortOrder = 1000 + start + 1;
        while (m.find()) {
            Topic theme = new Topic(m.group(1), m.group(2));
            theme.setDescription(m.group(3));
            // theme.setLastMessageAuthorId(m.group(5));
            theme.setLastMessageAuthor(m.group(6));
            theme.setIsNew(m.group(4) != null);
            theme.setLastMessageDate(Functions.parseForumDateTime(m.group(7), today, yesterday));
            theme.setForumId(forumId);
            theme.setSortOrder(Integer.toString(sortOrder++));
            res.add(theme);
        }
        m = lastPageStartPattern.matcher(pageBody);
        while (m.find()) {
            listInfo.setOutCount(Math.max(Integer.parseInt(m.group(2)), listInfo.getFrom()));
        }
        if (unreadInTop) {
            final int asc = -1;// новые вверху
            Collections.sort(res, new Comparator<Topic>() {
                @Override
                public int compare(Topic topic, Topic topic2) {
                    if (topic.getState() == topic2.getState())
                        return 0;
                    return (topic.getState() == Topic.FLAG_NEW) ? asc : (-asc);

                }
            });
        }
        if (res.size() == 0) {
            m = PatternExtensions.compile("<div class=\"errorwrap\">([\\s\\S]*?)</div>")
                    .matcher(pageBody);
            if (m.find()) {
                throw new NotReportException(Html.fromHtml(m.group(1)).toString(), new Exception(Html.fromHtml(m.group(1)).toString()));
            }
        }
        return res;
    }

}
