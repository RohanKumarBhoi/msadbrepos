// Databricks notebook source
// MAGIC %md
// MAGIC 
// MAGIC # Learning ADB Professionally
// MAGIC ## This is a simple notebook- lorem ipsum

// COMMAND ----------

val configs = Map(
  "fs.azure.account.auth.type" -> "OAuth",
  "fs.azure.account.oauth.provider.type" -> "org.apache.hadoop.fs.azurebfs.oauth2.ClientCredsTokenProvider",
  "fs.azure.account.oauth2.client.id" -> "5cbade6b-fdb2-4c18-9af0-7460058edb54",
  "fs.azure.account.oauth2.client.secret" -> dbutils.secrets.get(scope = "training-scope", key = "appsecret"),
  "fs.azure.account.oauth2.client.endpoint" -> "https://login.microsoftonline.com/72f988bf-86f1-41af-91ab-2d7cd011db47/oauth2/token")

// COMMAND ----------

  dbutils.fs.mount(
  source = "abfss://data@robhoidlsgen2source.dfs.core.windows.net/",
  mountPoint = "/mnt/data",
  extraConfigs = configs)

// COMMAND ----------

// MAGIC %fs
// MAGIC ls /mnt/data/Customers/

// COMMAND ----------

import spark.implicits._

case class Customer (customerid: Int, customername: String, address: String, credit: Int, status: Boolean, remarks: String)

// COMMAND ----------

val customersCsvLocation = "/mnt/data/Customers/*.csv"
val customers = 
  spark
    .read
    .option("inferSchema", true)
    .option("header", true)
    .option("sep", ",")
    .csv(customersCsvLocation)
    .as[Customer]

// COMMAND ----------

display(customers)

// COMMAND ----------

customers.createOrReplaceTempView("customers")

// COMMAND ----------

// MAGIC %sql
// MAGIC SELECT address AS CustomerLocation, COUNT(1) AS NumberOfCustomers FROM Customers
// MAGIC GROUP BY address
// MAGIC ORDER BY address

// COMMAND ----------

val getCustomerType = (credit: Int) => {
  if(credit < 10000) "Silver"
  else if(credit >= 10000 && credit < 25000) "Gold"
  else "Platinum"
}

spark.udf.register("getCustomerType", getCustomerType)

// COMMAND ----------

// MAGIC %sql
// MAGIC SELECT getCustomerType(credit) AS CustomerType, COUNT(*) AS NoOfCustomers
// MAGIC FROM customers
// MAGIC GROUP BY CustomerType
// MAGIC ORDER BY CustomerType

// COMMAND ----------

import java.io._
import java.net._
import java.util._

case class Language(documents: Array[LanguageDocuments], errors: Array[Any]) extends Serializable
case class LanguageDocuments(id: String, detectedLanguages: Array[DetectedLanguages]) extends Serializable
case class DetectedLanguages(name: String, iso6391Name: String, score: Double) extends Serializable

case class Sentiment(documents: Array[SentimentDocuments], errors: Array[Any]) extends Serializable
case class SentimentDocuments(id: String, score: Double) extends Serializable

case class RequestToTextApi(documents: Array[RequestToTextApiDocument]) extends Serializable
case class RequestToTextApiDocument(id: String, text: String, var language: String = "") extends Serializable

import javax.net.ssl.HttpsURLConnection
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import scala.util.parsing.json._

object SentimentDetector extends Serializable {
    val accessKey = "75cf4969e308419ca901d11e52600e94"
    val host = "https://robhoitextanalytics.cognitiveservices.azure.com/"
    val languagesPath = "/text/analytics/v2.1/languages"
    val sentimentPath = "/text/analytics/v2.1/sentiment"
    val languagesUrl = new URL(host+languagesPath)
    val sentimenUrl = new URL(host+sentimentPath)
    val g = new Gson

    def getConnection(path: URL): HttpsURLConnection = {
        val connection = path.openConnection().asInstanceOf[HttpsURLConnection]
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "text/json")
        connection.setRequestProperty("Ocp-Apim-Subscription-Key", accessKey)
        connection.setDoOutput(true)
        return connection
    }

    def prettify (json_text: String): String = {
        val parser = new JsonParser()
        val json = parser.parse(json_text).getAsJsonObject()
        val gson = new GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(json)
    }

    def processUsingApi(request: RequestToTextApi, path: URL): String = {
        val requestToJson = g.toJson(request)
        val encoded_text = requestToJson.getBytes("UTF-8")
        val connection = getConnection(path)
        val wr = new DataOutputStream(connection.getOutputStream())
        wr.write(encoded_text, 0, encoded_text.length)
        wr.flush()
        wr.close()

        val response = new StringBuilder()
        val in = new BufferedReader(new InputStreamReader(connection.getInputStream()))
        var line = in.readLine()
        while (line != null) {
            response.append(line)
            line = in.readLine()
        }
        in.close()
        return response.toString()
    }

    def getLanguage (inputDocs: RequestToTextApi): Option[Language] = {
        try {
            val response = processUsingApi(inputDocs, languagesUrl)
            val niceResponse = prettify(response)
            val language = g.fromJson(niceResponse, classOf[Language])
            if (language.documents(0).detectedLanguages(0).iso6391Name == "(Unknown)")
                return None
            return Some(language)
        } catch {
            case e: Exception => return None
        }
    }

    def getSentiment (inputDocs: RequestToTextApi): Option[Sentiment] = {
        try {
            val response = processUsingApi(inputDocs, sentimenUrl)
            val niceResponse = prettify(response)
            val sentiment = g.fromJson(niceResponse, classOf[Sentiment])
            return Some(sentiment)
        } catch {
            case e: Exception => return None
        }
    }
}

val toSentiment = (textContent: String) =>
        {
            val inputObject = new RequestToTextApi(Array(new RequestToTextApiDocument(textContent, textContent)))
            val detectedLanguage = SentimentDetector.getLanguage(inputObject)
            
            detectedLanguage match {
                case Some(language) =>
                    if(language.documents.size > 0) {
                        inputObject.documents(0).language = language.documents(0).detectedLanguages(0).iso6391Name
                        val sentimentDetected = SentimentDetector.getSentiment(inputObject)
                        sentimentDetected match {
                            case Some(sentiment) => {
                                if(sentiment.documents.size > 0) {
                                    sentiment.documents(0).score.toString()
                                }
                                else {
                                    "Error happened when getting sentiment: " + sentiment.errors(0).toString
                                }
                            }
                            case None => "Couldn't detect sentiment"
                        }
                    }
                    else {
                        "Error happened when getting language" + language.errors(0).toString
                    }
                case None => "Couldn't detect language"
            }
        }

// COMMAND ----------

spark.udf.register("toSentiment", toSentiment)

// COMMAND ----------

// MAGIC %sql
// MAGIC 
// MAGIC SELECT customerid, customername, remarks, toSentiment(remarks)
// MAGIC FROM customers
// MAGIC WHERE address = 'Chennai'