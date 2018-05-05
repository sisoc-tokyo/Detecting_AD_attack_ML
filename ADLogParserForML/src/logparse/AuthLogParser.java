package logparse;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.*;
import java.util.*;

import logparse.AuthLogUtil.Alert;
import logparse.AuthLogUtil.AlertType;

/**
 * Golden Ticket detection using Windows Event log 4674.
 * 
 * @version 1.0
 * @author Mariko Fujimoto
 */
public class AuthLogParser {

	// キーはアカウント名、値はEventLogDataオブジェクトのリスト。アカウント毎に分類するため
	private static Map<String, LinkedHashSet<EventLogData>> log;
	private static String outputDirName = null;

	// Initial value for timeCnt
	private static short TIME_CNT = Short.MAX_VALUE;

	// process name of PSEXESVC
	private static String PSEXESVC = "psexesvc";

	private static int EVENT_PROCESS = 4688;
	private static int EVENT_PRIV = 4672;
	private static int EVENT_PRIV_SERVICE = 4673;
	private static int EVENT_PRIV_OPE = 4674;
	private static int EVENT_TGT = 4768;
	private static int EVENT_ST = 4769;
	private static int EVENT_SHARE = 5140;
		
	private final static String SYSTEM_DIR="c:\\windows";
	private final static String REMOVE_CMD="c:\\temp\\tools\\backdoor";

	// Suspicious command list
	private List<String> suspiciousCmd = null;

	// admin account white list
	private List<String> adminWhiteList = null;
	
	// account white list
	private List<String> whiteList = null;

	// account name for detection
	private Set<String> accounts = new LinkedHashSet<String>();

	// account name for detection(Domain Admin Privilege accounts)
	private Set<String> adminAccounts = new LinkedHashSet<String>();

	private int detecctTargetcmdCnt = 0;

	private FileWriter filewriter = null;
	private BufferedWriter bw = null;
	private PrintWriter pw = null;

	// Data format
	private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

	private static long attackStartTime = 0;
	private int logCnt = 0;
	private int outlierNum = 0;
	private int trainNum = 0;
	private int testNum = 0;
	private int dataNum = 0;
	private int infectedNum = 0;

	private static boolean removeNoise = false;


	private void readCSV(String filename) {

		try {
			File f = new File(filename);
			BufferedReader br = new BufferedReader(new FileReader(f));
			String line;
			int eventID = -1;
			String date = "";
			LinkedHashSet<EventLogData> evSet = null;
			String accountName = "";
			String clientAddress = "";
			String serviceName = "";
			String processName = "";
			String shredName = "";
			String objectName = "";
			String privilege="";
			boolean isTargetEvent = false;

			// splitする際の上限回数
			int limit = 0;

			// categorize same operations based on time stamp
			short timeCnt = TIME_CNT;
			Date baseDate = null;
			Date logDate = null;
			EventLogData ev=null;
			while ((line = br.readLine()) != null) {
				int clientPort = 0;
				// Remove tab
				line = line.replaceAll("\\t", "");
				String[] data = line.split(",", 0);
				for (String elem : data) {
					if (line.contains("Microsoft-Windows-Security-Auditing,")) {
						date = data[1];
						eventID = Integer.parseInt(data[3]);
						if (line.contains(String.valueOf(EVENT_TGT)) || line.contains(String.valueOf(EVENT_ST))
								|| line.contains(String.valueOf(EVENT_PRIV_OPE))
								|| line.contains(String.valueOf(EVENT_PRIV))
								|| line.contains(String.valueOf(EVENT_PRIV_SERVICE))
								|| line.contains(String.valueOf(EVENT_PROCESS))
								|| line.contains(String.valueOf(EVENT_SHARE))) {
							isTargetEvent = true;
							try {
								// Get date
								logDate = sdf.parse(date);
								if (EVENT_ST == eventID && null == baseDate) {
									// this.EVENT_ST を起点として同じ時間帯に出ているログを調べる
									baseDate = sdf.parse(date);
									timeCnt--;
								} else if (null != baseDate) {
									// ログのタイムスタンプ差を調べる
									long logTime = logDate.getTime();
									long baseTime = baseDate.getTime();
									long timeDiff = (baseTime - logTime) / 1000;
									if (timeDiff > 1) {
										// 1秒以上離れているログには異なるtimeCntを割り当てる
										timeCnt--;
										baseDate = sdf.parse(date);
									}
								}

							} catch (ParseException e) {
								e.printStackTrace();
							}
						} else {
							isTargetEvent = false;
						}
					} else if (isTargetEvent) {
						if (elem.contains("アカウント名:") || elem.contains("Account Name:")) {
							accountName = parseElement(elem, ":", limit);
							if (accountName.isEmpty()) {
								continue;
							} else {
								// ドメイン名は取り除き、全て小文字にする
								accountName = accountName.split("@")[0].toLowerCase();
								if (null == log.get(accountName)) {
									evSet = new LinkedHashSet<EventLogData>();
								} else {
									evSet = log.get(accountName);
								}
								if (EVENT_PRIV == eventID) {
									// 4672はこれ以上情報がないので、アカウント名だけ取得し、管理者アカウントリストに入れる
									accounts.add(accountName);
									adminAccounts.add(accountName);
									continue;
								} else {
									// extract all users
									accounts.add(accountName);
								}
							}

						} else if (elem.contains("サービス名:") || elem.contains("Service Name:")) {
							serviceName = parseElement(elem, ":", limit);
						} else if (elem.contains("クライアント アドレス:") || elem.contains("Client Address:")
								|| elem.contains("ソース ネットワーク アドレス:") || elem.contains("Source Network Address:")
								|| elem.contains("送信元アドレス:") || elem.contains("Source Address:")) {
							elem = elem.replaceAll("::ffff:", "");
							clientAddress = parseElement(elem, ":", limit);

						} else if ((elem.contains("クライアント ポート:") || elem.contains("Client Port:")
								|| elem.contains("ソース ポート:")|| elem.contains("Source Port:"))) {
							try {
								clientPort = Integer.parseInt(parseElement(elem, ":", limit));
							} catch (NumberFormatException e) {
								// nothing
							}
							evSet.add(new EventLogData(date, clientAddress, accountName, eventID, clientPort,
									serviceName, processName, timeCnt));
							if (EVENT_SHARE != eventID) {
								// 5140は共有名の情報を取得してから格納する
								log.put(accountName, evSet);
							}
						} else if (elem.contains("オブジェクト名:") || elem.contains("Object Name:")) {
							objectName = parseElement(elem, ":", 2).toLowerCase();
						} else if ((elem.contains("プロセス名:") || elem.contains("Process Name:"))) {
							// プロセス名は":"が含まれることがあることを考慮
							processName = parseElement(elem, ":", 2).toLowerCase();
							if (removeNoise) {
								// Remove noise
								boolean isNoise = false;
								if (processName.equals(SYSTEM_DIR+"\\services.exe")) {
									if (objectName.contains(PSEXESVC)) {
										processName = objectName;
									} else {
										isNoise = true;
									}
								} else if (processName.equals(SYSTEM_DIR+"\\lsass.exe")) {
									isNoise = true;
								} else if (processName.contains(REMOVE_CMD)) {
									isNoise = true;
								}
								if (isNoise) {
									// Remove services.exe
									processName = "";
									continue;
								}
							}

							// 認証要求元は記録されない
							clientAddress = "";
							 ev = new EventLogData(date, clientAddress, accountName, eventID, clientPort,
									serviceName, processName, timeCnt);
							ev.setObjectName(objectName);
							if(eventID==EVENT_PROCESS || eventID==EVENT_PRIV_SERVICE){
								evSet.add(ev);
								log.put(accountName, evSet);
							}
							processName = "";
							objectName = "";
						} else if (elem.contains("共有名:")||elem.contains("Share Name:")) {
							 ev = new EventLogData(date, clientAddress, accountName, eventID, clientPort,
									serviceName, processName, timeCnt);
							shredName = parseElement(elem, ":", 2).toLowerCase();
							ev.setSharedName(shredName);
							evSet.add(ev);
							log.put(accountName, evSet);
							shredName = "";
						}  else if (eventID==EVENT_PRIV_OPE && (elem.contains("特権:")||elem.contains("Privileges:"))) {
							privilege = parseElement(elem, ":", 2).toLowerCase();
							if(ev!=null){
								ev.setPrivilege(privilege);
							}
							evSet.add(ev);
							log.put(accountName, evSet);
							privilege = "";
						}
					}
				}
			}
			br.close();
		} catch (IOException e) {
			System.out.println(e);
		}

	}

	private String parseElement(String elem, String delimiter, int limit) {
		String value = "";
		try {
			String elems[] = elem.trim().split(delimiter, limit);
			if (elems.length >= 2) {
				value = elems[1];
				value = value.replaceAll("\t", "");
			}
		} catch (RuntimeException e) {
			System.out.println(elem);
			e.printStackTrace();
		}
		if (value.isEmpty()) {
			value = "";
		}
		return value;
	}

	private void outputResults(Map map, String outputFileName) {
		try {
			// normal result
			filewriter = new FileWriter(outputFileName, true);
			bw = new BufferedWriter(filewriter);
			pw = new PrintWriter(bw);
			pw.println("date,eventID,account,ip,service,process,objectname,sharedname,target,alertlevel");

			System.out.println("Infected accounts and computers:");

			ArrayList<EventLogData> list = null;

			// アカウントごとに処理する
			for (String accountName : accounts) {
				LinkedHashSet<EventLogData> evS = log.get(accountName);
				if (null == evS) {
					continue;
				}
				// ソース IPが出ないイベントに、ソースIPをセットする
				setClientAddress(evS);

				// クライアントアドレス毎にログを保持するためのリスト(キー：クライアントアドレス)
				Map<String, LinkedHashSet> kerlog = new LinkedHashMap<String, LinkedHashSet>();

				// 同じ時間帯毎にログを保持するためのリスト(キー：クライアントアドレス)
				Map<Long, LinkedHashSet> timeBasedlog = new LinkedHashMap<Long, LinkedHashSet>();

				// さらにクライアントアドレスごとに分類し、GTが使われている可能性があるかを判定する
				for (EventLogData ev : evS) {
					if(null==ev){
						continue;
					}
					LinkedHashSet<EventLogData> evSet;
					String clientAddress = ev.getClientAddress();
					if (null != kerlog.get(clientAddress)) {
						evSet = kerlog.get(clientAddress);
					} else {
						evSet = new LinkedHashSet<EventLogData>();
					}
					evSet.add(ev);
					kerlog.put(ev.getClientAddress(), evSet);
				}

				for (Iterator it = kerlog.entrySet().iterator(); it.hasNext();) {
					Map.Entry<String, LinkedHashSet> entry = (Map.Entry<String, LinkedHashSet>) it.next();
					String computer = entry.getKey();
					if (!accountName.isEmpty() && !computer.isEmpty()) {
						this.dataNum++;
					}
				}
				// 異常値どうかか判定
				if (adminAccounts.contains(accountName)) {
					isOutlier(kerlog, accountName);
				}
				// 同じ時間帯のログごとに処理
				list = new ArrayList<EventLogData>(evS);
				Collections.reverse(list);
				for (EventLogData ev : list) {
					if(null==ev){
						continue;
					}
					LinkedHashSet<EventLogData> evSet;
					if (null != timeBasedlog.get(ev.getTimeCnt())) {
						evSet = timeBasedlog.get(ev.getTimeCnt());
					} else {
						evSet = new LinkedHashSet<EventLogData>();
					}
					evSet.add(ev);
					timeBasedlog.put(ev.getTimeCnt(), evSet);
				}

				// 結果をファイルに出力する
				outputLogs(timeBasedlog, accountName);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			pw.close();
			try {
				bw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void isOutlier(Map<String, LinkedHashSet> kerlog, String accountName) {
		// kerlogは端末毎に分類されたログ
		for (Iterator it = kerlog.entrySet().iterator(); it.hasNext();) {
			boolean isTGTEvent = false;
			boolean isSTEvent = false;
			short isGolden = 0;
			Map.Entry<String, LinkedHashSet> entry = (Map.Entry<String, LinkedHashSet>) it.next();
			String computer = entry.getKey();
			LinkedHashSet<EventLogData> evS = (LinkedHashSet<EventLogData>) entry.getValue();
			LinkedHashMap<Long,Alert> attackTimeCnt = new LinkedHashMap<Long,Alert>();
			for (EventLogData ev : evS) {
				// 同じアカウント・端末・時間帯のログに同じtimeCntを割り当てる
				// アカウント・端末を連結させた文字列のハッシュコードとタイムカウントを加算する
				long timeCnt = (ev.getAccountName() + ev.getClientAddress()).hashCode() + ev.getTimeCnt();
				ev.settimeCnt(timeCnt);
				int eventID = ev.getEventID();
				// 4768/4769が記録されているかを調べる
				if (eventID == 4768) {
					isTGTEvent = true;
				} else if (eventID == EVENT_ST) {
					isSTEvent = true;
				}
			}
			if (!isTGTEvent && isSTEvent) {
				// 4768が記録されていないのに、4769が記録されている
				isGolden = 1;
				System.out.println("Account: " + accountName + ", Computer: " + computer);
				for (EventLogData ev : evS) {
					if (EVENT_ST == ev.getEventID()) {
						ev.setIsGolden(isGolden);
						ev.setAlertLevel(Alert.SEVERE);
					}
				}
			}
			Set<String> commands = new LinkedHashSet<String>();
			for (EventLogData ev : evS) {
				if(ev.getEventID()==EVENT_PRIV_OPE &&!this.adminWhiteList.contains(accountName) 
						&& this.adminAccounts.contains(accountName)){
					// 管理者リストに含まれていないのに、特権を使っている
					isGolden = 1;
					ev.setIsGolden(isGolden);
					ev.setAlertLevel(Alert.SEVERE);
				}
				if (EVENT_PRIV_SERVICE == ev.getEventID() || EVENT_PRIV_OPE == ev.getEventID()
						|| EVENT_PROCESS == ev.getEventID()) {
					// 4673,4674に記録されたプロセスのパスがシステムディレクトリでない
					if(!ev.getProcessName().contains(SYSTEM_DIR)){
						isGolden = 1;
						ev.setIsGolden(isGolden);
						ev.setAlertLevel(Alert.SEVERE);
					}
					String command[] = ev.getProcessName().split("\\\\");
					String commandName = "";
					if (null != command) {
						commandName = command[command.length - 1];
					}
					// 攻撃者がよく実行するコマンドを実行している
					for (String cmd : suspiciousCmd) {
						if (commandName.equals(cmd)) {
							isGolden = 1;
							ev.setIsGolden(isGolden);
							commands.add(ev.getProcessName());
						}
						if (EVENT_PRIV_OPE == ev.getEventID()) {
							// psexecが実行されている
							if (ev.getObjectName().contains(this.PSEXESVC)) {
								isGolden = 1;
								ev.setIsGolden(isGolden);
								ev.setAlertLevel(Alert.SEVERE);
							}
						}
					}
				} else if (5140 == ev.getEventID()) {
					// 管理共有が使用されている
					if (ev.getSharedName().contains("\\c$")) {
						isGolden = 1;
						ev.setIsGolden(isGolden);
						ev.setAlertLevel(Alert.SEVERE);
					}
				} 
			}
			// 実行された不審なコマンドの種類数
			int detecctcmdCnt = commands.size();
			double commandExecuterate = (double) detecctcmdCnt / this.detecctTargetcmdCnt;
			Alert alertLevel = Alert.NONE;
			if (commandExecuterate > AuthLogUtil.ALERT_SEVIRE) {
				alertLevel = Alert.SEVERE;
			} else if (commandExecuterate > AuthLogUtil.ALERT_WARNING) {
				alertLevel = Alert.WARNING;
			} else if (commandExecuterate > 0) {
				alertLevel = Alert.NOTICE;
			}
			//outlierと判定したログと同時刻のログをマークし、同じアラートレベルを設定する
			for (EventLogData ev : evS) {
				if (1 == ev.isGolden()) {
					attackTimeCnt.put(ev.getTimeCnt(),ev.getAlertLevel());
					if(ev.getAlertLevel()==Alert.NONE){
						ev.setAlertLevel(alertLevel);
					}
				}
			}
			for (EventLogData ev : evS) {
				if (null!=attackTimeCnt.get(ev.getTimeCnt())) {
					if(0==ev.isGolden()){
						isGolden = 1;
						ev.setIsGolden(isGolden);
					}
					if(ev.getAlertLevel()==Alert.NONE){
						ev.setAlertLevel(attackTimeCnt.get(ev.getTimeCnt()));
					}
				}
			}
			if (1 == isGolden && !accountName.isEmpty() && !computer.isEmpty()) {
				infectedNum++;
				System.out.println("Account: " + accountName + ", Computer: " + computer);
			}
		}
	}

	// not used now
	private void mergeLogs(Map<Long, LinkedHashSet> kerlog, String accountName) {
		for (Iterator it = kerlog.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, LinkedHashSet> entry = (Map.Entry<Long, LinkedHashSet>) it.next();
			LinkedHashSet<EventLogData> evS = (LinkedHashSet<EventLogData>) entry.getValue();
			Map<String, LinkedHashSet> map = new LinkedHashMap<String, LinkedHashSet>();
			LinkedHashSet<EventLogData> set = null;
			// 端末毎に分類する
			String clientAddress = "";
			for (EventLogData ev : evS) {
				if (!ev.getClientAddress().isEmpty()) {
					if (null != map.get(ev.getClientAddress())) {
						set = map.get(ev.getClientAddress());
					} else {
						set = new LinkedHashSet<EventLogData>();
					}
				} else {
					// 端末情報が出ないログは、直前に処理した端末と同じとみなす
					if (null != map.get(ev.getClientAddress())) {
						set = map.get(clientAddress);
					} else {
						set = new LinkedHashSet<EventLogData>();
					}
				}
				set.add(ev);
				map.put(ev.getClientAddress(), set);
			}
			// 同じtimeCnt,IPのデータをマージする
			String event = "";
			int clientPort = 0;
			String serviceName = "";
			String processName = "";
			short isGolden = 0;
			for (Iterator itTerm = map.entrySet().iterator(); itTerm.hasNext();) {
				Map.Entry<String, LinkedHashSet> entryTerm = (Map.Entry<String, LinkedHashSet>) itTerm.next();
				clientAddress = entryTerm.getKey();
				LinkedHashSet<EventLogData> evSTerm = (LinkedHashSet<EventLogData>) entryTerm.getValue();
				for (EventLogData ev : evS) {
					event = event += String.valueOf(ev.getEventID());
					clientPort = clientPort += ev.getClientPort();
					serviceName = serviceName += ev.getServiceName();
					processName = processName += ev.getProcessName();
					if (1 == ev.isGolden()) {
						isGolden = ev.isGolden();
					}
				}
			}
		}

	}

	private void outputLogs(Map<Long, LinkedHashSet> kerlog, String accountName) {
		for (Iterator it = kerlog.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, LinkedHashSet> entry = (Map.Entry<Long, LinkedHashSet>) it.next();
			LinkedHashSet<EventLogData> evS = (LinkedHashSet<EventLogData>) entry.getValue();
			String target = "";
			long logTime = 0;
			for (EventLogData ev : evS) {
				int eventID = ev.getEventID();
				if (eventID == EVENT_PRIV_OPE || eventID == EVENT_PRIV_SERVICE || eventID == EVENT_PROCESS
						|| eventID == EVENT_SHARE) {
					try {
						logTime = sdf.parse(ev.getDate()).getTime();
					} catch (ParseException e1) {
						e1.printStackTrace();
					}
					this.logCnt++;
					if (0 != attackStartTime) {
						// 攻撃開始時刻が指定されている
						if (1 == ev.isGolden()) {
							// 異常データ
							target = "outlier";
							this.outlierNum++;
						} else if (logTime < attackStartTime) {
							// 攻撃開始前は学習用データとする
							target = "train";
							this.trainNum++;
						} else if (logTime >= attackStartTime) {
							// 攻撃開始後のログはテストデータとする
							target = "test";
							this.testNum++;
						}
					}
					// UNIX Timeの計算
					long time = 0;
					try {
						time = sdf.parse(ev.getDate()).getTime();
					} catch (ParseException e) {
						e.printStackTrace();
					}
					pw.println(ev.getDate() + "," + ev.getEventID() + "," + accountName + "," + ev.getClientAddress()
							+ "," + ev.getServiceName() + "," + ev.getProcessName() + "," + ev.getObjectName() + ","
							+ ev.getSharedName() + "," + target+ "," + ev.getAlertLevel());
				}
			}
		}

	}
/*
	private void outputTimeSeriseLogs(Map<Long, LinkedHashSet> kerlog, String accountName) {
		long timeCnt = 0;
		for (Iterator it = kerlog.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, LinkedHashSet> entry = (Map.Entry<Long, LinkedHashSet>) it.next();
			LinkedHashSet<EventLogData> evS = (LinkedHashSet<EventLogData>) entry.getValue();
			String target = "";
			EventLogData prevEv = null;
			for (EventLogData ev : evS) {
				if (1 == ev.isGolden()) {
					target = "outlier";
				} else if (currentTrainNum <= trainNum || timeCnt == ev.getTimeCnt()) {
					target = "train";
					currentTrainNum++;
				} else {
					target = "test";
				}
				this.id++;
				pw3.print(this.id + ",");
				if (null == prevEv) {
					pw3.print("-,-,-,-,-");
				} else {
					pw3.print(prevEv.getEventID() + "," + accountName + "," + prevEv.getClientAddress() + ","
							+ prevEv.getServiceName() + "," + prevEv.getProcessName());
				}
				pw3.println("," + ev.getEventID() + "," + accountName + "," + ev.getClientAddress() + ","
						+ ev.getServiceName() + "," + ev.getProcessName() + "," + target);
				prevEv = new EventLogData(ev.getDate(), ev.getClientAddress(), accountName, ev.getEventID(),
						ev.getClientPort(), ev.getServiceName(), ev.getProcessName(), ev.getTimeCnt());
			}
			timeCnt = entry.getKey();
		}
	}
*/
	/**
	 * Judge whether the log is outlier
	 * 
	 * @param inputDirname
	 */
	public void detectGolden(String inputDirname) {
		File dir = new File(inputDirname);
		File[] files = dir.listFiles();

		for (File file : files) {
			String filename = file.getName();
			if (filename.endsWith(".csv")) {
				readCSV(file.getAbsolutePath());
			} else {
				continue;
			}
		}
		outputResults(log, this.outputDirName + "/" + "eventlog.csv");
	}

	private void detelePrevFiles(String outDirname) {
		Path path = Paths.get(outDirname);
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(path, "*.*")) {
			for (Path deleteFilePath : ds) {
				Files.delete(deleteFilePath);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void printUseage() {
		System.out.println("Useage");
		System.out.println(
		"{iputdirpath} {outputdirpath} {suspicious command list file} {date when attack starts} {adminlist} (true)");
		System.out.println(
				"Mark logs recoeded after {date when attack starts} as test data. "
						+ "Date shold be specified 'yyyy/MM/dd HH:mm:ss' format.)");
		System.out.println(
				"If you specity 'true', remove noise log(service.exe etc) for detection");
	}

	/**
	 * Read suspicious command list
	 * 
	 * @param inputfilename
	 */
	private void readSuspiciousCmd(String inputfilename) {

		File f = new File(inputfilename);
		suspiciousCmd = new ArrayList<String>();
		try {
			BufferedReader br = new BufferedReader(new FileReader(f));
			String line;
			while ((line = br.readLine()) != null) {
				suspiciousCmd.add(line);
			}
			this.detecctTargetcmdCnt = this.suspiciousCmd.size();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * Read admin list
	 * 
	 * @param inputfilename
	 */
	private void readAdminList(String inputfilename) {

		File f = new File(inputfilename);
		adminWhiteList = new ArrayList<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(f));
			String line;
			while ((line = br.readLine()) != null) {
				adminWhiteList.add(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void readWhiteList(String inputfilename) {

		File f = new File(inputfilename);
		this.whiteList = new ArrayList<String>();
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(f));
			String line;
			while ((line = br.readLine()) != null) {
				this.whiteList.add(line);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void setClientAddress(LinkedHashSet<EventLogData> evS) {
		List<EventLogData> list = new ArrayList<EventLogData>(evS);
		// 時刻の昇順に並べる
		Collections.reverse(list);
		String clientAddress = "";
		for (EventLogData ev : list) {
			if(null==ev){
				continue;
			}
			if (ev.getEventID() == EVENT_ST) {
				clientAddress = ev.getClientAddress();
			} else if (ev.getEventID() == EVENT_PRIV_SERVICE || ev.getEventID() == EVENT_PRIV_OPE
					|| ev.getEventID() == EVENT_PROCESS) {
				if (!clientAddress.isEmpty()) {
					ev.setClientAddress(clientAddress);
				}
			}
		}
	}

	private void outputDetectionRate() {
		System.out.println();
		System.out.println("Total amount of events: " + this.logCnt);
		System.out.println("Total amount of accounts & computers: " + this.dataNum);
		System.out.println("outlier: " + this.outlierNum);
		System.out.println("train: " + this.trainNum);
		System.out.println("test: " + this.testNum);
		System.out.println("TP(accounts & computers): " + this.infectedNum);
		System.out.println("TN(accounts & computers): " + (this.dataNum - this.infectedNum));
	}

	public static void main(String args[]) throws ParseException {
		AuthLogParser authLogParser = new AuthLogParser();
		String inputdirname = "";
		String commandFile = "";
		String adminlist = "";
		String whitelist = "";
		if (args.length < 3) {
			printUseage();
		} else
			inputdirname = args[0];
		outputDirName = args[1];
		commandFile = args[2];
		if (args.length > 3) {
			try{
			attackStartTime = sdf.parse(args[3]).getTime();
			} catch (ParseException e) {
				System.out.println("Date shold be specified 'yyyy/MM/dd HH:mm:ss' format.");
				throw(e);
			}
		}
		if (args.length > 4) {
			adminlist = args[4];
		}
		if (args.length > 5) {
			removeNoise = Boolean.parseBoolean(args[5]);
		}
		log = new LinkedHashMap<String, LinkedHashSet<EventLogData>>();
		authLogParser.readSuspiciousCmd(commandFile);
		authLogParser.readAdminList(adminlist);
		//authLogParser.readWhiteList(whitelist);
		authLogParser.detelePrevFiles(outputDirName);
		authLogParser.detectGolden(inputdirname);
		authLogParser.outputDetectionRate();
	}

}
