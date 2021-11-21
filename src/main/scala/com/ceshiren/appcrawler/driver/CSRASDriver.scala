package com.ceshiren.appcrawler.driver

import com.ceshiren.appcrawler._
import com.ceshiren.appcrawler.core.CrawlerConf
import com.ceshiren.appcrawler.model.URIElement
import com.ceshiren.appcrawler.utils.Log.log
import com.ceshiren.appcrawler.utils.DynamicEval
import org.openqa.selenium.Rectangle

import java.awt.{BasicStroke, Color}
import java.io.File
import javax.imageio.ImageIO
import scala.sys.process._

/**
 * Created by seveniruby on 18/10/31.
 */
class CSRASDriver extends ReactWebDriver {
  DynamicEval.init()
  var conf: CrawlerConf = _
  private var adb = ""
  private val session = requests.Session()

  //csras本地映射的地址
  var systemPort = ""
  var packageName = ""
  var activityName = ""
  var uuid = ""
  var otherApps: List[String] = List[String]()

  def this(url: String = "http://127.0.0.1:4723/wd/hub", configMap: Map[String, Any] = Map[String, Any]()) {
    this

    log.info(s"url=${url}")
    packageName = configMap.getOrElse("appPackage", "").toString
    activityName = configMap.getOrElse("appActivity", "").toString
    systemPort = configMap.getOrElse("systemPort", "").toString
    uuid = configMap.getOrElse("uuid", "").toString
    adb = getAdb()
    //    log.info(configMap.toString())
    if (systemPort.equals("")) {
      log.info("No systemPort Set In Config,Use Default Port:7778")
      systemPort = "7778"
    }
    otherApps = configMap.getOrElse("otherApps", List[String]()).asInstanceOf[List[String]]
    // 安装辅助APP
    installOtherApps()
    // 确认设备中Driver状态
    val apkPath = adb(s"shell 'pm list packages | grep com.hogwarts.csruiautomatorserver ||:'")
    if (apkPath.indexOf("com.hogwarts.csruiautomatorserver") == -1) {
      log.info("CSRASDriver Not Exist In Device,Need Install")
    }

    //设备driver连接设置
    initDriver()

    if (configMap.getOrElse("noReset", "").toString.toLowerCase.equals("false")
      && !packageName.contains("tencent")) {
      adb(s"shell pm clear ${packageName}")
    } else {
      log.info("need need to reset app")
    }

    if (packageName.nonEmpty && configMap.getOrElse("dontStopAppOnReset", "").toString.toLowerCase.equals("true")) {
      adb(s"shell am start -W -n ${packageName}/${activityName}")
    }
    else if (packageName.nonEmpty && configMap.getOrElse("dontStopAppOnReset", "").toString.toLowerCase.equals("false")) {
      adb(s"shell am start -S -W -n ${packageName}/${activityName}")
    } else {
      log.info("dont run am start")
    }
  }

  // 安装辅助APP
  def installOtherApps(): Unit = {
    log.info("Install otherApps")
    otherApps.foreach(app => {
      log.info(s"Install App To Device From ${app}")
      adb(s"install '${app}'")
    })
  }


  def setPackage(): Unit = {
    try {
      val r = session.post(s"${getServerUrl}/package?package=${packageName}")
      log.info(r)
    } catch {
      case e: Exception => log.error(e.printStackTrace())
    }

  }

  //设备driver连接设置
  def initDriver(): Unit = {
    // 给Driver设置权限，使驱动可以自行开启辅助功能
    adb(s"shell pm grant com.hogwarts.csruiautomatorserver android.permission.WRITE_SECURE_SETTINGS")
    // 启动Driver
    driverStart()

    Thread.sleep(3000)
    //设置端口转发，将driver的端口映射到本地，方便进行请求
    adb(s"forward tcp:${systemPort} tcp:7777")
    // 等待远程服务连接完毕
    // todo:将等待改为通过轮询接口判断设备上的服务是否启动
    setPackage()
    //获取包过滤参数
    //    val packageFilter =
    log.info(s"Driver Filter Package is ${getPackageFilter}")

  }

  def driverStart(): Unit = {
    // 有时候应用一次停不掉，两次确保停止
    adb(s"shell am force-stop com.hogwarts.csruiautomatorserver")
    adb(s"shell am force-stop com.hogwarts.csruiautomatorserver")
    adb(s"shell settings put secure enabled_accessibility_services com.hogwarts.csruiautomatorserver/.MainActivity")
    adb(s"shell am start com.hogwarts.csruiautomatorserver/com.hogwarts.csruiautomatorserver.MainActivity")
  }

  //拼接Driver访问地址
  def getServerUrl: String = {
    val driverUrl = "http://127.0.0.1"
    driverUrl + ":" + systemPort
  }

  def getPackageFilter: String = {
    //通过发送请求，设置关注的包名，过滤掉多余的数据
    session.get(s"${getServerUrl}/package").text()
  }

  override def event(keycode: String): Unit = {
    adb(s"shell input keyevent ${keycode}")
    log.info(s"event=${keycode}")
  }

  //todo: outside of Raster 问题
  override def getDeviceInfo(): Unit = {
    val size = adb(s"shell wm size").split(' ').last.split('x')
    screenHeight = size.last.trim.toInt
    screenWidth = size.head.trim.toInt
    log.info(s"screenWidth=${screenWidth} screenHeight=${screenHeight}")
  }

  override def swipe(startX: Double = 0.9, startY: Double = 0.1, endX: Double = 0.9, endY: Double = 0.1): Unit = {
    val xStart = startX * screenWidth
    val xEnd = endX * screenWidth
    val yStart = startY * screenHeight
    val yEnd = endY * screenHeight
    log.info(s"swipe screen from (${xStart},${yStart}) to (${xEnd},${yEnd})")
    adb(s"shell input swipe ${xStart} ${yStart} ${xEnd} ${yEnd}")
  }


  override def screenshot(): File = {
    val file = File.createTempFile("tmp", ".png")
    log.info(file.getAbsolutePath)
    val cmd = s"${adb} exec-out screencap -p"
    log.info(cmd)
    (cmd #> file).!!
    file
  }

  //todo: 重构到独立的trait中


  override def click(): this.type = {
    val center = currentURIElement.center()
    adb(s"shell input tap ${center.x} ${center.y}")
    this
  }

  override def tap(): this.type = {
    click()
  }

  override def tapLocation(x: Int, y: Int): this.type = {
    val pointX = x * screenWidth
    val pointY = y * screenHeight
    adb(s"shell input tap ${pointX} ${pointY}")
    this
  }

  override def longTap(): this.type = {
    val center = currentURIElement.center()
    log.info(s"longTap element in (${center.x},${center.y})")
    adb(s"shell input swipe ${center.x} ${center.y} ${center.x + 0.1} ${center.y + 0.1} 2000")
    this
  }

  override def back(): Unit = {
    adb(s"shell input keyevent 4")
  }

  override def backApp(): Unit = {
    adb(s"shell am start -W -n ${packageName}/${activityName}")
  }

  override def getPageSource(): String = {
    session.get(s"${getServerUrl}/source").text()
  }

  override def getAppName(): String = {
    val appName = session.get(s"${getServerUrl}/fullName").text().split('/').head
    log.info(s"PackageName is ${appName}")
    appName
  }

  override def getUrl(): String = {
    val appUrl = session.get(s"${getServerUrl}/fullName").text().split('/').last.stripLineEnd
    log.info(s"ActivityName is ${appUrl}")
    appUrl
  }

  override def getRect(): Rectangle = {
    //selenium下还没有正确的赋值，只能通过api获取
    if (currentURIElement.getHeight != 0) {
      //log.info(s"location=${location} size=${size} x=${currentURIElement.x} y=${currentURIElement.y} width=${currentURIElement.width} height=${currentURIElement.height}" )
      new Rectangle(currentURIElement.getX, currentURIElement.getY, currentURIElement.getHeight, currentURIElement.getWidth)
    } else {
      log.error("rect height < 0")
      return null
    }
  }

  override def sendKeys(content: String): Unit = {
    tap()
    adb(s"shell input text ${content}")
  }

  override def launchApp(): Unit = {
    //driver.get(capabilities.getCapability("app").toString)
    back()
  }

  override def findElements(element: URIElement, findBy: String): List[AnyRef] = {
    List(element)
  }

  override def reStartDriver(waitTime: Int = 2000): Unit = {
    log.info("reStartDriver")
    driverStart()
    Thread.sleep(waitTime)
    log.info(s"Wait ${waitTime}ms")
    setPackage()
    // 重启服务后需要通过呼出和收回系统通知栏触发page source刷新，保证能够获取到最新的界面数据
    adb("shell cmd statusbar expand-notifications")
    Thread.sleep(1500)
    adb("shell cmd statusbar collapse")
    Thread.sleep(waitTime)
    log.info(s"Wait ${waitTime}ms")
  }

  def getAdb(): String = {
    var adbCMD = ""
    if (System.getenv("ANDROID_HOME") != null) {
      adbCMD = List(System.getenv("ANDROID_HOME"), "platform-tools/adb").mkString(File.separator)
    } else {
      adbCMD = "adb"
    }
    if (uuid != null && uuid.nonEmpty) {
      s"${adbCMD} -s ${uuid}"
    } else {
      adbCMD
    }
  }

  override def sendText(text: String): Unit = {
    adb(s"shell am broadcast -a ADB_INPUT_TEXT --es msg '${text}'")
  }

  override def adb(command: String): String = {
    shell(s"${adb} ${command}")
  }

  def shell(cmd: String): String = {
    log.info(cmd)
    val result = cmd.!!
    log.info(result)
    result
  }

}

