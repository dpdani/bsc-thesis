package eu.spaziodati.metrics.streams

 . . .

class lambdaStream extends StartableStream {
  override val config: lambdaConfig = new lambdaConfig
  override val logger: Logger = LoggerFactory.getLogger(getClass)
  override val name = "lambda"

  override def createStreamTopology(builder: StreamsBuilder): Topology = {
    builder.stream(config.inputTopic, Consumed.`with`(
      new JsonSerde[lambdaDebeziumKey],
      new JsonSerde[Debeziumlambda]
    ))
      .filter(
        (_, record) =>
          record != null &&
            record.after.isDefined
      )
      .map(
        (key, record) => KeyValue.pair(
          key.id, record.after.get
        )
      )
      .groupBy(
        (_, record: lambda) => lambdaDebeziumOutputKey(
          record.email,
          record.day.toLocalDate,
          record.klass,
        ),
        Grouped.`with`(
          new JsonSerde[lambdaDebeziumOutputKey],
          new JsonSerde[lambda]
        )
      )
      .aggregate(
        () => new Outputlambda("", LocalDate.now(),"", 0),
        (aggKey: lambdaDebeziumOutputKey, newValue: lambda, aggValue: Outputlambda) => {
          if (aggValue.email == "") {
            aggValue.email = aggKey.email
            aggValue.day = aggKey.day
            aggValue.klass = aggKey.klass
          }
          aggValue.n += 1
          aggValue
        },
        Materialized.`with`(
          new JsonSerde[lambdaDebeziumOutputKey],
          new JsonSerde[Outputlambda]
        )
      )
      .toStream()
      .mapValues(
        record => new lambdaDebeziumOutput("lambda", record),
      )
      .to(config.outputTopic, Produced.`with`(
        new JsonSerde[lambdaDebeziumOutputKey],
        new JsonSerde[lambdaDebeziumOutput]
      ))
    builder.build()
  }
}
