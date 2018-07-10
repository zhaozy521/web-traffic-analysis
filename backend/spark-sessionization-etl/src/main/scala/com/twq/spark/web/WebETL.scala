package com.twq.spark.web

import com.twq.parser.dataobject.BaseDataObject
import com.twq.prepaser.PreParsedLog
import org.apache.spark.{HashPartitioner, SparkConf}
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.{Encoders, Row, SparkSession}

/**
  *  网站流量离线分析Spark ETL入口(应用层面)
  */
object WebETL {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf
    if (args.isEmpty) {
      conf.setMaster("local")
    }
    conf.set("spark.serializer", classOf[KryoSerializer].getName)
    conf.set("spark.kryo.registrator", classOf[WebRegistrator].getName)

    //预处理输出的基本路径
    val wdPreparsedLogBaseDir = conf.getOption("spark.web.etl.inputBaseDir")
      .getOrElse("hdfs://master:9999/user/hive/warehouse/rawdata.db/web")
    //主ETL输出路径
    val outputBaseDir = conf.getOption("spark.web.etl.outputBaseDir")
      .getOrElse("hdfs://master:9999/user/hadoop-twq/traffic-analysis/web")
    //日志的时间
    val dateStr = conf.getOption("spark.web.etl.startDate").getOrElse("20180615")
    //分区数
    val numPartitions = conf.getInt("spark.web.etl.numberPartitions", 5)

    conf.setAppName(s"WebETL-${dateStr}")

    val spark = SparkSession.builder().config(conf).getOrCreate()

    //预处理输出的具体路径
    val preParsedLogPath =
      s"$wdPreparsedLogBaseDir/year=${dateStr.substring(0, 4)}/month=${dateStr.substring(0, 6)}/day=${dateStr}"

    /**
      * transform后数据为：
      *  PreParsedLog(profileId1, pv, queryString, serverTime....)
      *  PreParsedLog(profileId1, mc, queryString, serverTime....)
      *  PreParsedLog(profileId2, pv, queryString, serverTime....)
      *  ..........
      *  PreParsedLog(profileId3, ev, queryString, serverTime....)
      *  PreParsedLog(profileIdn, hb, queryString, serverTime....)
      */
    //读取预处理后的日志
    val parsedLogRDD = spark.read.parquet(preParsedLogPath)
      //将DataFrame转成Dataset[PreParsedLog]再转成RDD[PreparsedLog]
      .map(transform(_))(Encoders.bean(classOf[PreParsedLog])).rdd
      //将RDD[PreParsedLog]转成RDD[(CombinedId, BaseDataObject)]
      .flatMap(p => WebLogParser.parse(p))
    /**
      * WebLogParser.parse转换后数据为：
      * (CombinedId(profileId1,user1), BaseDataObject(profileId1,user1,pv,client_ip.....))
      * (CombinedId(profileId1,user1), BaseDataObject(profileId1,user1,mc,client_ip.....))
      * (CombinedId(profileId2,user2), BaseDataObject(profileId2,user2,pv,client_ip.....))
      *  ............
      *  (CombinedId(profileId3,user3), BaseDataObject(profileId3,user3,ev,client_ip.....))
      *  (CombinedId(profileIdn,usern), BaseDataObject(profileIdn,usern,pv,client_ip.....))
      */

    //将parsedLogRDD按照key进行分组
    parsedLogRDD.groupByKey(new HashPartitioner(numPartitions)).mapPartitionsWithIndex((index, iterator) => {
      //groupByKey后的每一个分区的数据为：
      /**
        * 转换后数据为：
        * (CombinedId(profileId1,user1), List(BaseDataObject(profileId1,user1,pv,client_ip.....),
        *                                     BaseDataObject(profileId1,user1,mc,client_ip.....)))
        * (CombinedId(profileId2,user2), List(BaseDataObject(profileId2,user2,pv,client_ip.....),
        *                                     BaseDataObject(profileId2,user2,mc,client_ip.....),
        *                                     BaseDataObject(profileId2,user2,ev,client_ip.....)
        *                                     BaseDataObject(profileId2,user2,hb,client_ip.....)))
        *  ............
        *  (CombinedId(profileId3,user3), List(BaseDataObject(profileId3,user3,ev,client_ip.....)))
        *  (CombinedId(profileIdn,usern), List(BaseDataObject(profileIdn,usern,pv,client_ip.....),
        *                                      BaseDataObject(profileIdn,usern,mc,client_ip.....),
        *                                      BaseDataObject(profileIdn,usern,pv,client_ip.....)))
        */
      //处理每一个分区的数据
      val partitionProcessor = new PartitionProcessor(index, iterator, outputBaseDir, dateStr)
      partitionProcessor.run()
      //不需要返回，所以返回空
      Iterator[Unit]()
      //触发action操作
    }).foreach( (_: Unit) => {})

    spark.stop()
  }

  private def transform(row: Row): PreParsedLog = {
    val p = new PreParsedLog
    p.setClientIp(row.getAs[String]("clientIp"))
    p.setCommand(row.getAs[String]("command").toString)
    p.setMethod(row.getAs[String]("method"))
    p.setProfileId(row.getAs[Int]("profileId"))
    p.setQueryString(row.getAs[String]("queryString"))
    p.setServerIp(row.getAs[String]("serverIp"))
    p.setServerPort(row.getAs[Int]("serverPort"))
    p.setServerTime(row.getAs[String]("serverTime"))
    p.setUriStem(row.getAs[String]("uriStem"))
    p.setUserAgent(row.getAs[String]("userAgent"))
    p
  }
}
