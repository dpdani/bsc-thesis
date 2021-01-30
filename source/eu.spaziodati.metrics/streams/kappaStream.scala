package eu.spaziodati.metrics.streams

 . . .

class kappaStream extends StartableStream {
  override val config: kappaConfig = new kappaConfig
  override val logger: Logger = LoggerFactory.getLogger(getClass)
  override val name = "kappa"

  override def createStreamTopology(builder: StreamsBuilder): Topology = {
    builder.stream(config.inputTopic, Consumed.`with`(new JsonSerde[kappaDebeziumKey], new JsonSerde[Debeziumkappa]))
      .filter(
        (_, record) =>
          record != null &&
            record.after.isDefined &&
            record.after.get.klass.isDefined &&
            record.after.get.user.isDefined &&
            record.after.get.sub_class.isDefined &&
            // Only interested in "credits_buckets" (-> "c") category.
            record.after.get.klass.get == "c"
      )
      // Only interested in records that have consumed some credits.
      .filter(
        (_, record) => {
          val Right(postSave) = circe.jawn.decode[kappaPostSave](record.after.get.post_save)
          postSave.cost.isDefined
        }
      )
      // Extract `after` field and source table ID
      .map(
        (key, record) => KeyValue.pair(
          key.id, record.after.get
        )
      )
      .groupBy(
        (_, record: kappa) => kappaDebeziumOutputKey(
          record.user.get,
          record.day.toLocalDate,
          record.klass.get,
          record.sub_class.get,
        ),
        Grouped.`with`(
          new JsonSerde[kappaDebeziumOutputKey],
          new JsonSerde[kappa]
        )
      )
      .aggregate(
        () => new Outputkappa(-1, LocalDate.now(), "", "", 0),
        (aggKey: kappaDebeziumOutputKey, newValue: kappa, aggValue: Outputkappa) => {
              if (aggValue.user == -1) {
                aggValue.user = aggKey.user
                aggValue.day = aggKey.day
                aggValue.klass = aggKey.klass
                aggValue.sub_class = aggKey.sub_class
              }
              val Right(postSave) = circe.jawn.decode[kappaPostSave](newValue.post_save)
              aggValue.C += postSave.cost.getOrElse(0)
              aggValue
            },
        Materialized.`with`(
          new JsonSerde[kappaDebeziumOutputKey],
          new JsonSerde[Outputkappa]
        )
      )
      .toStream()
      .mapValues(
        record => new kappaDebeziumOutput("upsert", record),
      )
      .to(config.outputTopic, Produced.`with`(new JsonSerde[kappaDebeziumOutputKey], new JsonSerde[kappaDebeziumOutput]))
    builder.build()
  }
}
