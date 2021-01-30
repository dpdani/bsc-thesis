package eu.spaziodati.metrics.streams

 . . .

class thetaStream extends StartableStream {
  override val config: thetaConfig = new thetaConfig
  override val logger: Logger = LoggerFactory.getLogger(getClass)
  override val name = "theta"

  override def createStreamTopology(builder: StreamsBuilder): Topology = {
    builder.stream(config.inputTopic, Consumed.`with`(
        new JsonSerde[thetaDebeziumKey],
        new JsonSerde[Debeziumtheta]
    ))
      .filter(
        (_, record) =>
          record != null &&
            record.after.isDefined &&
            record.after.get.user.isDefined &&
            record.after.get.status.isDefined &&
            // Filter out rows that are not finalised, i.e. will get updated
            // status == "done", or "error" if the row is finalised.
            List("done", "error").contains(record.after.get.status.get)
      )
      .map(
        (key, record) => KeyValue.pair(
          key.id, record.after.get
        )
      )
      .groupBy(
        (_, record: theta) => thetaDebeziumOutputKey(
          record.user.get,
          record.day.toLocalDate,
        ),
        Grouped.`with`(
          new JsonSerde[thetaDebeziumOutputKey],
          new JsonSerde[theta]
        )
      )
      .aggregate(
        () => new Outputtheta(-1, LocalDate.now(),0),
        (aggKey: thetaDebeziumOutputKey, newValue: theta, aggValue: Outputtheta) => {
          if (aggValue.user == -1) {
            aggValue.user = aggKey.user
            aggValue.day = aggKey.day
          }
          aggValue.n += 1
          aggValue
        },
        Materialized.`with`(
          new JsonSerde[thetaDebeziumOutputKey],
          new JsonSerde[Outputtheta]
        )
      )
      .toStream()
      .mapValues(
        record => new thetaDebeziumOutput("upsert", record),
      )
      .to(config.outputTopic, Produced.`with`(
        new JsonSerde[thetaDebeziumOutputKey],
        new JsonSerde[thetaDebeziumOutput]
      ))
    builder.build()
  }
}
