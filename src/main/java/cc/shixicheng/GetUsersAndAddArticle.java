package cc.shixicheng;

import cc.shixicheng.util.HttpUtil;
import cc.shixicheng.util.StreamUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * 添加csdn用户并给文章点赞
 */
public class GetUsersAndAddArticle {

    public Set<String> users = new HashSet<>();

    public void followAndAddLike(String userNo, String password) {
        UserToken token = login(userNo, password);
        if (token != null && token.getToken() != null) {
            getCSDNUsers(token.getUserName());
            users.stream().forEach(user ->
                    follow(user, token.getToken(), token.getUserName())
            );
            Map<String, String> firstArticleIds = getFirstArticleId(users);
            firstArticleIds.keySet().stream().forEach(userName ->
                    addLike(userName, firstArticleIds.get(userName), token.getToken(), token.userName));
        }
    }

    /**
     * @return key:userName,value:articleId
     */
    public Map<String, String> getFirstArticleId(Set<String> userNames) {
        Map<String, String> articleIdMap = new HashMap<>();
        String blogUrlTemplate = "https://blog.csdn.net/%s/article/list/1";
        userNames.stream().forEach(userName -> {
            String blogUrl = String.format(blogUrlTemplate, userName);
            try {
                InputStream inputStream = HttpUtil.doGet(blogUrl);
                String content = StreamUtil.inputStreamToString(inputStream, "UTF-8");
                String articleIdRegex = "https://blog.csdn.net/" + userName + "/article/details/([0-9]{8})";
                Pattern pattern = Pattern.compile(articleIdRegex);
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    articleIdMap.put(userName, matcher.group(1));
                    break;
                }
            } catch (IOException e) {
                System.out.println("http get failed");
                e.printStackTrace();
            }
        });
        return articleIdMap;
    }

    public void getCSDNUsers(String userName) {
        if (users.size() > 1800) {
            return;
        }
        Set<String> userList = parseFans(mePage(userName));
        /*userList = userList.stream().filter(user ->
                user.indexOf("wenxin_") == -1 && user.indexOf("qq_") == -1
        ).collect(Collectors.toSet());*/
        if (userList != null && userList.size() > 0) {
            synchronized (users) {
                users.stream().forEach(user -> {
                    userList.contains(user);
                    userList.remove(user);
                });
                users.addAll(userList);
                System.out.println("已找到" + users.size() + "个用户");
            }
            userList.stream().forEach(user -> getCSDNUsers(user));
        }
    }

    public void follow(String username, String token, String myUserName) {
        String followUrl = "https://my.csdn.net/index.php/follow/do_follow";
        String params = "username=" + username;
        try {
            Map<String, String> header = new HashMap<>();
            header.put("cookie", "UserName=" + myUserName + "; UserToken=" + token);
            HttpUtil.doPostForm(followUrl, header, params);
            System.out.println(username + " follow success");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String mePage(String username) {
        String follerUrl = "https://me.csdn.net/" + username;
        try {
            Map<String, String> headers = new HashMap<>();
            InputStream inputStream = HttpUtil.doGet(follerUrl, headers);
            String response = StreamUtil.inputStreamToString(inputStream, "UTF-8");
            return response;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Set<String> parseFans(String pageContext) {
        if (pageContext == null || pageContext.length() == 0) {
            return new HashSet<>();
        }
        String fansRegex = "a class=\"fans_title\" href=\"https://me.csdn.net/(.+)\"";
        Pattern pattern = Pattern.compile(fansRegex);
        Matcher matcher = pattern.matcher(pageContext);
        Set<String> fanses = new HashSet<>();
        while (matcher.find()) {
            fanses.add(matcher.group(1));
        }
        return fanses;
    }

    /**
     * @desc 分析得知，点赞采用get方式请求，接口https://blog.csdn.net/?/phoenix/article/digg?ArticleId=?，第一个?是用户名，第二个?是文章id
     * 。这个接口及时点赞，也是取消点赞，它两返回值返回值不同
     * 点赞{"status":true,"digg":2,"bury":"0"}
     * 取消点赞{"status":true,"digg":1,"bury":"0"}
     */
    public void addLike(String userName, String articleId, String token, String myUserNo) {
        String url = "https://blog.csdn.net/" + userName + "/phoenix/article/digg?ArticleId=" + articleId;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("cookie", "UserName=" + myUserNo + "; " +
                    "UserToken=" + token);
            InputStream inputStream = HttpUtil.doGet(url, headers);
            String response = StreamUtil.inputStreamToString(inputStream, "UTF-8");
            inputStream.close();
            JSONObject jsonObject = JSONObject.parseObject(response);
            if (jsonObject.get("digg") != null && "0".equals(jsonObject.get("digg").toString())) {
                addLike(userName, articleId, token, myUserNo);
            }
            System.out.println("点赞成功:" + "https://blog.csdn.net/" + userName + "/article/details/" + articleId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 模拟登陆，并从cookie中拿出token和用户名
     *
     * @param username
     * @param password
     */
    public UserToken login(String username, String password) {
        String url = "https://passport.csdn.net/v1/register/pc/login/doLogin";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("accept", "application/json;charset=UTF-8");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("loginType", "1");
        jsonObject.put("pwdOrVerifyCode", password);
        jsonObject.put("userIdentification", username);
        UserToken userToken = new UserToken();
        try {
            Map<String, List<String>> repHeaders = HttpUtil.doPostJSON(url, headers,
                    jsonObject.toJSONString());
            List<String> setCookies = repHeaders.get("Set-Cookie");
            if (setCookies != null && setCookies.size() > 0) {
                setCookies.stream().forEach(cook -> {
                    if (cook.contains("UserToken=")) {
                        String userTokenRegex = "UserToken=(.*); Max";
                        Pattern pattern = Pattern.compile(userTokenRegex);
                        Matcher matcher = pattern.matcher(cook);
                        while (matcher.find()) {
                            userToken.setToken(matcher.group(1));
                        }
                    }
                    if (cook.contains("UserName=")) {
                        String userTokenRegex = "UserName=(.*); Max";
                        Pattern pattern = Pattern.compile(userTokenRegex);
                        Matcher matcher = pattern.matcher(cook);
                        while (matcher.find()) {
                            userToken.setUserName(matcher.group(1));
                        }
                    }
                });
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        if (userToken.getToken() != null && !userToken.getToken().equals("")) {
            System.out.println("login success, token is " + userToken.getToken());
        } else {
            System.out.println("login failed, userNo is " + userToken.getUserName());
        }
        return userToken;
    }


    @Data
    public static class UserInfo {
        @JSONField(name = "user_name")
        private String userName;
        @JSONField(name = "click_count")
        private String clickCount;
        @JSONField(name = "comment_count")
        private String commentCount;
        @JSONField(name = "blog_count")
        private String blogCount;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UserInfo userInfo = (UserInfo) o;
            return Objects.equals(userName, userInfo.userName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userName);
        }
    }

    @Data
    public static class UserToken {
        private String token;
        private String userName;
    }

}
