package edu.ggc.it.banner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import android.util.Log;

/**
 * Banner is a class comprising a series of static methods for retrieving course information from
 * the Banner web site. These methods make an HTTPS connection to Banner and parse the relevant
 * data out of the HTML. This class will not function correctly below API Level 10 due to
 * differences in the SSL implementation in prior versions.
 * 
 * @author Jacob
 *
 */
public class Banner {
	private static final String BANNER_URL = "https://ggc.gabest.usg.edu";
	private static final String TAG = "BannerInterface";
	// for class data
	private static final String DATE_RANGE = "Date Range";
	private static final String TIME = "Time";
	private static final String DAYS = "Days";
	private static final String WHERE = "Where";
	private static final String INSTRUCTORS = "Instructors";
	private static final BannerForm p_display_courses =
			new BannerForm("/pls/B400/bwckctlg.p_display_courses", false,
					new NameValuePair[]{
					new BasicNameValuePair("term_in", "201302"),
					new BasicNameValuePair("one_subj", ""),
					new BasicNameValuePair("sel_crse_strt", "0000"),
					new BasicNameValuePair("sel_crse_end", "9999"),
					new BasicNameValuePair("sel_subj", ""),
					new BasicNameValuePair("sel_coll", ""),
					new BasicNameValuePair("sel_schd", ""),
					new BasicNameValuePair("sel_divs", ""),
					new BasicNameValuePair("sel_dept", ""),
					new BasicNameValuePair("sel_levl", ""),
					new BasicNameValuePair("sel_attr", ""),
			});
	private static final BannerForm p_disp_listcrse =
			new BannerForm("/pls/B400/bwckctlg.p_disp_listcrse", false,
					new NameValuePair[]{
					new BasicNameValuePair("term_in", "201302"),
					new BasicNameValuePair("subj_in", ""),
					new BasicNameValuePair("crse_in", ""),
					new BasicNameValuePair("schd_in", "")
			});
	
	/**
	 * Retrieves a mapping of course number to course name for all courses offered under the
	 * specified subject. Valid values for the subject parameter are the values in the
	 * ds_subject_codes string array in strings.xml.
	 * 
	 * @param subject	the 3-4 letter subject code of the subject to find courses for (e.g. "ITEC")
	 * @return a Map of course numbers to course names (e.g. {"3860": "Software Development I"})
	 */
	public static Map<String, String> getCourseNumbers(String subject){
		// TODO: synchronization
		p_display_courses.set("one_subj", subject);
		String response = p_display_courses.request();
		Map<String, String> courses = new HashMap<String, String>();
		List<String> titles = scrapeInner(response, "A", "/pls/B400/bwckctlg.p_disp_course_detail?");
		
		for (int i = 0; i < titles.size(); i++){
			String href = titles.get(i);
			int numStart = href.indexOf(" ")+1;
			int numEnd = href.indexOf(" ", numStart);
			String number = href.substring(numStart, numEnd);
			int nameStart = href.indexOf(" ", numEnd+1)+1;
			String name = href.substring(nameStart);
			courses.put(number, name);
		}
		
		return courses;
	}
	
	/**
	 * Retrieves a mapping of section numbers to CRNs for all sections for the given course
	 * offered this semester. The course is identified by the subject code and course
	 * number.
	 * 
	 * @param subject	the 3-4 letter subject code of the course (e.g. "ITEC")
	 * @param course	the 4 digit course number (e.g. "3860")
	 * @return a Map of section numbers to CRNs (e.g. {"01": "20709"})
	 */
	public static Map<String, String> getSections(String subject, String course){
		final String separator = " - ";
		
		// TODO: synchronization
		p_disp_listcrse.set("subj_in", subject);
		p_disp_listcrse.set("crse_in", course);
		String response = p_disp_listcrse.request();
		Map<String, String> sections = new HashMap<String, String>();
		List<String> titles = scrapeInner(response, "A", "/pls/B400/bwckschd.p_disp_detail_sched?");
		
		for (int i = 0; i < titles.size(); i++){
			String href = titles.get(i);
			int crnStart = href.indexOf(separator)+separator.length();
			int crnEnd = href.indexOf(separator, crnStart);
			String crn = href.substring(crnStart, crnEnd);
			int sectStart = href.indexOf(separator, crnEnd+separator.length())+separator.length();
			String sectionId = href.substring(sectStart);
			sections.put(sectionId, crn);
		}
		
		return sections;
	}
	
	/**
	 * Retrieves a Course object containing schedule information about the given course.
	 * Refer to the Course documentation for a description of the information contained
	 * therein.
	 * 
	 * @see Course
	 * 
	 * @param subject	the 3-4 letter subject code of the course (e.g. "ITEC")
	 * @param course	the 4 digit course number (e.g. "3860")
	 * @param crn		the CRN (e.g. "20709")
	 * @return a Course object containing the course's schedule information
	 */
	public static Course getCourse(String subject, String course, String crn){
		// TODO: find text less likely to change to identify the table to parse
		final String sectionSummary = "This layout table is used to present the sections found";
		final String nameHref = "/pls/B400/bwckschd.p_disp_detail_sched";
		final String summary = "This table lists the scheduled meeting times and assigned instructors for this class..";
		final String separator = " - ";
		final String sepreg = Pattern.quote(separator);
		final int namePos = 0; // position of the course name in the array of course name parts
		final int sectionPos = 3; // position of the section number in the array of course name parts
		
		Course result = null;
		
		// TODO: synchronization
		p_disp_listcrse.set("subj_in", subject);
		p_disp_listcrse.set("crse_in", course);
		String response = p_disp_listcrse.request();
		// filter for just this section
		List<String> sectionTables = scrapeInner(response, "TABLE", sectionSummary);
		String sectionTable = null;
		for (String table: sectionTables){
			if (table.indexOf(crn) != -1){
				sectionTable = table;
				break;
			}
		}
		
		// we were only passed the CRN, so get the section number from the HTML
		String title = scrapeInner(sectionTable, "A", nameHref).get(0);
		// String.split wants a regex, so Pattern.quote escapes our string so
		// that we're matching on the actual characters and they're not interpreted
		// as special regex characters.
		String[] nameParts = title.split(sepreg);
		String courseName = nameParts[namePos];
		int sectionNumber = Integer.parseInt(nameParts[sectionPos]);
		
		List<Map<String, String>> courseData = scrapeTable(sectionTable, summary);
		Map<String, List<Meeting>> meetingTimes = new HashMap<String, List<Meeting>>();
		
		for (Map<String, String> meeting: courseData){
			String dateRange = meeting.get(DATE_RANGE);
			String[] dates = dateRange.split(sepreg);
			
			SimpleDateFormat bannerDate = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
			Date beginDate = new Date();
			Date endDate = new Date();
			
			try {
				beginDate = bannerDate.parse(dates[0]);
			} catch (ParseException pe) {
				Log.e(TAG, "Bad date format " + dates[0], pe);
			}
			
			try {
				endDate = bannerDate.parse(dates[1]);
			} catch (ParseException pe) {
				Log.e(TAG, "Bad date format " + dates[1], pe);
			}
			
			String timeRange = meeting.get(TIME);
			String[] times = timeRange.split(sepreg);
			
			SimpleDateFormat bannerTime = new SimpleDateFormat("hh:mm a", Locale.US);
			Date beginTime = new Date();
			Date endTime = new Date();
			
			try {
				beginTime = bannerTime.parse(times[0]);
			} catch (ParseException pe) {
				Log.e(TAG, "Bad time format " + times[0], pe);
			}
			
			try {
				endTime = bannerTime.parse(times[1]);
			} catch (ParseException pe) {
				Log.e(TAG, "Bad time format " + times[1], pe);
			}
			
			String days = meeting.get(DAYS);
			String location = meeting.get(WHERE);
			String instructors = meeting.get(INSTRUCTORS);
			List<Meeting> meetings;
			Meeting meet = new Meeting(subject, instructors, beginDate, endDate, beginTime, endTime);
			
			for (int i = 0; i < days.length(); i++){
				String day = days.substring(i, i+1);
				
				if (meetingTimes.containsKey(day)){
					meetings = meetingTimes.get(day);
				} else{
					meetings = new ArrayList<Meeting>();
					meetingTimes.put(day, meetings);
				}
				
				meetings.add(meet);
			}
		}
		
		result = new Course(subject, courseName, Integer.parseInt(course), sectionNumber,
				Integer.parseInt(crn), meetingTimes);
		
		return result;
	}
	
	
	/**
	 * Searches a string containing HTML for a table tag matching the given matches.
	 * Note that only the tag itself is checked for matches; the data between the
	 * table tags is not checked. The table will be searched for headings (th tags)
	 * to use as field names, and then the cells (td tags) in each row will be mapped
	 * to its respective column heading in the form {heading: value}. Returns a list of
	 * these mappings.
	 * 
	 * Note: at the moment, this method serves the relatively restricted purpose of
	 * extracting course schedule information for the getCourse() method, and may
	 * not be appropriate for other uses.
	 * 
	 * @param html		the HTML containing the table to scrape
	 * @param matches	a series of Strings identifying which table to scrape
	 * @return a list of mappings of column headings to the respective column values
	 */
	private static List<Map<String, String>> scrapeTable(String html, String... matches){
		List<Map<String, String>> results = new ArrayList<Map<String, String>>();
		List<String> headers = null;
		
		// TODO: this method assumes uppercase tag names. This is what Banner uses,
		// but it could change in the future
		String tableData = scrapeInner(html, "TABLE", matches).get(0);
		headers = scrapeInner(tableData, "TH");
		
		Map<String, String> tableValues = new HashMap<String, String>(headers.size());
		List<String> data = scrapeInner(tableData, "TD");
		
		for (int i = 0; i < data.size(); i++){
			int headerIndex = i % tableValues.size();
			if (headerIndex == 0 && i != 0){
				results.add(tableValues);
				tableValues = new HashMap<String, String>(headers.size());
			}
			
			tableValues.put(headers.get(headerIndex), data.get(i));
		}
		
		results.add(tableValues);
		
		return results;
	}
	
	/**
	 * Retrieves the inner HTML (the text between the opening and closing tags) for
	 * each tag with the specified tag name in an HTML string where the text of the
	 * opening tag contains the all of the given match strings.
	 * 
	 * @param html		the HTML string to be searched
	 * @param tag		the name of the tag to retrieve the inner HTML of
	 * @param matches	an array of strings that the open tag must contain
	 * @return a list of strings containing the inner HTML of each matching tag
	 */
	private static List<String> scrapeInner(String html, String tag, String... matches){
		List<String> results = new ArrayList<String>();
		String matched = null, line = null;
		int begin = 0, end = -1;
		boolean inTag = false;
		
		searchHTML:
		do{
			begin = html.indexOf("<" + tag, begin);
			if (begin != -1){
				end = html.indexOf(">", begin);
				if (end != -1){
					matched = html.substring(begin, end);
					begin = end+1;
					
					for (String match: matches){
						if (matched.indexOf(match) == -1)
							continue searchHTML;
					}
					
					// this tag matches; grab the inner HTML
					end = html.indexOf("</" + tag, begin);
					if (end == -1) // tag not closed properly; just grab everything
						end = html.length();
					line = html.substring(begin, end);
					results.add(line);
					begin = end;
				} else{
					begin++;
				}
			}
		} while (begin != -1);
		
		return results;
	}
	
	/**
	 * The BannerForm class represents an individual page on the Banner web site
	 * and the parameters that it requires to return valid results. Default values
	 * for all possible parameters must be provided when the class is instantiated,
	 * and for each request, those parameters with values different from the default
	 * are set to the necessary values and the page is requested.
	 * 
	 * This class relies on the static final field BANNER_URL in the outer Banner class;
	 * this must be the protocol (e.g. https://) and domain name (e.g. ggc.gabest.usg.edu)
	 * of the Banner web site.
	 * 
	 * @author Jacob
	 *
	 */
	private static class BannerForm{
		private String path;
		private NameValuePair[] defaultParms;
		private Map<String, String> currentParms;
		private boolean isPOST;
		
		/**
		 * Constructs a BannerForm representing the given page on Banner. You must specify
		 * whether to send a POST request or a GET request. If the URL contains a question
		 * mark followed by a series of parameters (such as /somepage?x=y&a=1&foo=bar),
		 * this is a GET request. If the page is accessed by filling out a form and clicking
		 * a submit button, and the URL of the result page does not contain a question mark
		 * followed by the parameter list, this is a POST request; you will need to get
		 * the parameter names and values from the HTML source. The parameters are specified
		 * as NameValuePairs, a class provided by Apache for precisely this purpose.
		 * 
		 * @param path		the path (not including the protocol or domain) of the page on Banner
		 * @param isPOST	true if this page expects an HTTP POST request, false if it expects a GET
		 * @param parms		an array of parameter names mapped to default values
		 */
		public BannerForm(String path, boolean isPOST, NameValuePair... parms){
			this.path = path;
			this.isPOST = isPOST;
			defaultParms = parms;
			currentParms = new HashMap<String, String>();
			for (NameValuePair parm: parms)
				currentParms.put(parm.getName(), parm.getValue());
		}
		
		/**
		 * Set the value of parameter name for this request, with name and value as
		 * separate parameters.
		 * 
		 * @param name	the parameter name
		 * @param value	the value to use for this parameter
		 * @return true if the parameter name is valid, false if not
		 */
		public boolean set(String name, String value){
			if (!currentParms.containsKey(name))
				return false;
			currentParms.put(name, value);
			return true;
		}
		
		/**
		 * Set the value of a parameter for this request, with the parameter name
		 * and value in a NameValuePair object.
		 * 
		 * @param parm	a NameValuePair containing the new parameter value
		 * @return true if the parameter name is valid, false if not
		 */
		public boolean set(NameValuePair parm){
			if (!currentParms.containsKey(parm.getName()))
				return false;
			currentParms.put(parm.getName(), parm.getValue());
			return true;
		}
		
		/**
		 * Send a POST or GET request to the Banner page represented by this
		 * BannerForm using either the default value or the value set with
		 * one of the set() methods for each parameter passed to the constructor
		 * and return the resulting data as a String. After the request completes,
		 * all parameters are reset to their default values, and new requests
		 * must call the set() methods again to set the values of any parameters
		 * they need.
		 * 
		 * This method does not guarantee the format of the results. If any of
		 * the constructor arguments were specified incorrectly, if you try to
		 * access a page that requires a login to view, or if Banner
		 * encounters an internal error (among other things), this method may
		 * return an error page, an empty string, or whatever else the server
		 * decides to respond to the request with.
		 * 
		 * @return the HTML of the response page, or an empty string if the request fails
		 */
		public String request(){
			HttpClient client = new DefaultHttpClient();
			HttpUriRequest request = null;
			BufferedReader reader = null;
			StringBuffer ret = new StringBuffer();
			
			try{
				if (isPOST){
					HttpPost post = new HttpPost(BANNER_URL + path);
					List<NameValuePair> parms = new ArrayList<NameValuePair>(defaultParms.length);
					for (Map.Entry<String, String> parm: currentParms.entrySet())
						parms.add(new BasicNameValuePair(parm.getKey(), parm.getValue()));
					post.setEntity(new UrlEncodedFormEntity(parms));
					request = post;
				} else{
					String requestPath = BANNER_URL + path + "?";
					for (Map.Entry<String, String> parm: currentParms.entrySet())
						requestPath += parm.getKey() + "=" + parm.getValue() + "&";
					HttpGet get = new HttpGet(requestPath);
					request = get;
				}
				HttpResponse response = null;
				response = client.execute(request);
				HttpEntity entity = response.getEntity();
				InputStream stream = entity.getContent();
				Header encoding = entity.getContentEncoding();
				reader = new BufferedReader(new InputStreamReader(stream,
						(encoding == null? "iso-8859-1": encoding.getValue())));
				for (String line = reader.readLine(); line != null; line = reader.readLine())
					ret.append(line);
				reader.close();
			} catch (UnsupportedEncodingException uee){
				Log.e(TAG, "Failed to send Banner request", uee);
			} catch (ClientProtocolException cpe) {
				Log.e(TAG, "Failed to send Banner request", cpe);
			} catch (IOException ioe) {
				Log.e(TAG, "Failed to send Banner request", ioe);
			} finally{
				for (NameValuePair parm: defaultParms)
					currentParms.put(parm.getName(), parm.getValue());
			}
			
			return ret.toString();
		}
	}
}
