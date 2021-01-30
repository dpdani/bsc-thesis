package eu.spaziodati.metrics.connectors

 . . .

class PostgresSinkTask extends SinkTask {
  private var connection: Connection = _
  private val statementsCache: mutable.Map[(String, String), PreparedStatement] = mutable.Map[(String, String), PreparedStatement]()
  private val logger = LoggerFactory.getLogger(getClass)
  private val timeTravelTables = List("alpha", "beta", "mu")

  override def start(propsJ: util.Map[String, String]): Unit = {
    logger.info("Running with scala version: " + scala.util.Properties.scalaPropOrElse("version.number", "unknown"))
    val props = propsJ.asScala
    val properties = new Properties()
    properties.setProperty("stringtype", "unspecified")
    // Line below doesn't actually do anything, but
    // prevents the Driver from not getting into the Uber-Jar
    DriverManager.registerDriver(
      new org.postgresql.Driver()
    )
    connection = DriverManager.getConnection(props(PostgresSinkConfigConstants.URL), properties)
  }

  override def stop(): Unit = {
    connection.close()
  }

  private def makeQuestionMarks(n: Int): String = List.tabulate(n)(_ => '?').mkString(",")

  private def bindParameters(stmt: PreparedStatement, values: Iterable[AnyRef]): PreparedStatement = {
    var index = 1
    values.foreach({
      case value: ArrayList[String] =>
        val valueArray = connection.createArrayOf("varchar", value.toArray())
        stmt.setArray(index, valueArray)
        index += 1
      case value =>
        stmt.setObject(index, value)
        index += 1
    })
    stmt
  }

  private def doUpsert(table: String, record: Map[String, AnyRef], key: Map[String, AnyRef]): Unit = {
    val stmt = statementsCache.getOrElseUpdate(
      (table, "upsert"),
      connection.prepareStatement(
        s"""  INSERT INTO $table("${record.keys.mkString("\",\"")}")
           |  VALUES (${makeQuestionMarks(record.size)})
           |  ON CONFLICT ("${key.keys.mkString("\",\"")}") DO UPDATE
           |    SET "${record.keys.mkString("\"= ?,\"")}" = ?
           |    WHERE $table."${key.keys.mkString("\"= ? AND " + table +".\"")}" = ?;
           |""".stripMargin
      )
    )
    bindParameters(stmt, record.values ++ record.values ++ key.values).execute()
  }

  private def doUpsertTimeTravel(table: String, recordWithNulls: Map[String, AnyRef], key: Map[String, AnyRef]): Unit = {
    val record = recordWithNulls.filter {
      case (_, v) => v != null
    }
    val stmt = statementsCache.getOrElseUpdate(
      (table, s"tt:upsert(${record.keys.mkString(",")})"),
      connection.prepareStatement(
        // N.B. With autocommit enabled (such is the default)
        // these statements execute within a transaction.
        s"""  UPDATE $table
           |  SET validity = tstzrange(lower(validity), now())
           |  WHERE ${key.keys.mkString("= ? AND ")} = ?
           |    AND current_timestamp <@ validity
           |    AND NOT EXISTS (
           |      SELECT 1 FROM $table
           |      WHERE ${record.keys.mkString("= ? AND ")} = ?
           |        AND current_timestamp <@ validity
           |    );
           |
           |  INSERT INTO $table (${record.keys.mkString(", ")}, validity)
           |  SELECT ${makeQuestionMarks(record.size)}, tstzrange(now(), 'infinity', '[)')
           |  WHERE NOT EXISTS (
           |    SELECT 1 FROM $table
           |    WHERE ${record.keys.mkString("= ? AND ")} = ?
           |      AND current_timestamp <@ validity
           |  );
           |""".stripMargin
      )
    )
    bindParameters(stmt,
      // UPDATE
      key.values ++ record.values ++
      // INSERT
      record.values ++ record.values
    ).execute()
  }

  private def doUpsertlambda(table: String, record: Map[String, AnyRef], key: Map[String, AnyRef]): Unit = {
    val stmt = statementsCache.getOrElseUpdate(
      (table, "email to id"),
      connection.prepareStatement(
        s"SELECT public.user_email_to_id(?) AS user_id;"
      )
    )
    val rs = bindParameters(stmt, List(record("user_id"))).executeQuery()
    rs.next()
    val userId = rs.getObject("user_id")
    if (userId == null) {
      logger.error(s"Got null while converting email '${record("user_id")}', in doUpsertlambda.")
    }
    else {
      val mutableRecord = mutable.Map(record.toSeq: _*)
      mutableRecord("user_id") = userId
      val mutableKey = mutable.Map(key.toSeq: _*)
      mutableKey("user_id") = userId
      doUpsert(table, Map(mutableRecord.toSeq: _*), Map(mutableKey.toSeq: _*))
    }
  }

  private def doDelete(table: String, key: Map[String, AnyRef]): Unit = {
    val stmt = statementsCache.getOrElseUpdate(
      (table, "delete"),
      connection.prepareStatement(
        s"""  DELETE FROM $table
           |  WHERE ${key.keys.mkString("= ? AND ")} = ?;""".stripMargin
      )
    )
    bindParameters(stmt, key.values).execute()
  }

  private def doDeleteTimeTravel(table: String, key: Map[String, AnyRef]): Unit = {
    val stmt = statementsCache.getOrElseUpdate(
      (table, "tt:delete"),
      connection.prepareStatement(
        s"""  UPDATE $table
           |  SET validity = tstzrange(lower(validity), now())
           |  WHERE ${key.keys.mkString("= ? AND ")} = ?
           |    AND current_timestamp <@ validity;
           |""".stripMargin
      )
    )
    bindParameters(stmt, key.values).execute()
  }

  private def extractAfterRecord(data: mutable.Map[Object, Object]): Map[String, AnyRef] = {
    data("after").asInstanceOf[util.Map[Object, Object]].asScala.map { case (k, v) =>
      (k.toString, v)
    }.toMap
  }

  private def extractTs(data: mutable.Map[Object, Object]): Long = {
    data("source").asInstanceOf[util.Map[Object, Object]].asScala.map {
      case (k, v) => (k.toString, v)
    }.map {
      case ("ts_ms", ts) => Some(ts.asInstanceOf[Long])
      case _ => None
    }.filter(x => x.isDefined).head.get
  }

  override def put(recordsJ: util.Collection[SinkRecord]): Unit = {
    val records = recordsJ.asScala
    var table = "unknown"
    for (record <- records) {
      table = record.topic()
        .split("\\.").takeRight(2).mkString(".")  // Avoid the Debezium's assigned database name
      val key = record.key().asInstanceOf[util.Map[String, AnyRef]].asScala.toMap
      val json = record.value().asInstanceOf[util.Map[Object, Object]].asScala
      if (json != null) {  // Ignore Debezium tombstone events
        var data = Map[String, AnyRef]()
        try {
          data = extractAfterRecord(json)
        }
        catch {
          case e: NullPointerException => // there is no `after` field; discard.
        }
        json("op") match {
          case "c" | "r" =>
            if (timeTravelTables.contains(table)) {
              doUpsertTimeTravel(table, data, key)
            }
            else {
              doUpsert(table, data, key)
            }
          case "u" =>
            if (timeTravelTables.contains(table)) {
              doUpsertTimeTravel(table, data, key)
            }
            else {
              doUpdate(table, data, key)
            }
          case "d" =>
            if (timeTravelTables.contains(table)) {
              doDeleteTimeTravel(table, key)
            }
            else {
              doDelete(table, key)
            }
          case "upsert" => doUpsert(table, data, key)
          case "lambda" => doUpsertlambda(table, data, key)
        }
      }
    }
    if (records.nonEmpty) {
      logger.info(s"Processed ${records.size} records for table $table.")
    }
  }

  override def version(): String = "1.0.0"
}
