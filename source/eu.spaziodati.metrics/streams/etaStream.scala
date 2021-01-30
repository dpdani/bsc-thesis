package eu.spaziodati.metrics.streams

 . . .

class etaStream extends StartableStream {
  override val config: etaConfig = new etaConfig
  override val logger: Logger = LoggerFactory.getLogger(getClass)
  override val name = "eta"

  override def createStreamTopology(builder: StreamsBuilder): Topology = {
    builder.stream(config.inputTopic, Consumed.`with`(new JsonSerde[etaDebeziumKey], new JsonSerde[Debeziumeta]))
      .filter(
        (_, record) =>
          record != null &&
            record.after.isDefined &&
            record.after.get.klass.isDefined &&
            record.after.get.status.isDefined &&
            record.after.get.cost.isDefined &&
            record.after.get.user.isDefined &&
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
        (_, record: eta) => etaDebeziumOutputKey(
          record.user.get,
          record.day.toLocalDate,
          record.klass.get,
        ),
        Grouped.`with`(
          new JsonSerde[etaDebeziumOutputKey],
          new JsonSerde[eta]
        )
      )
      .aggregate(
        () => new Outputeta(-1, LocalDate.now(), "", 0, 0),
        (aggKey: etaDebeziumOutputKey, newValue: eta, aggValue: Outputeta) => {
          if (aggValue.user == -1) {
            aggValue.user = aggKey.user
            aggValue.day = aggKey.day
            aggValue.klass = aggKey.klass
          }
          aggValue.n += 1
          aggValue.C += newValue.cost.get
          aggValue
        },
        Materialized.`with`(
          new JsonSerde[etaDebeziumOutputKey],
          new JsonSerde[Outputeta],
        )
      )
      .toStream()
      .mapValues(
        record => new etaDebeziumOutput("upsert", record),
      )
      .to(config.outputTopic, Produced.`with`(new JsonSerde[etaDebeziumOutputKey], new JsonSerde[etaDebeziumOutput]))
    builder.build()
  }
}
