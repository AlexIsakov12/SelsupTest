package org.alexisakov;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 *
 * @author Александр Исаков
 * @version 1.0
 * Класс для работы с API Честного знака
 * Реализовано создание документа для ввода в оборот товара, произведенного в РФ
 * Написан простой ResponseHandler, т.к. не все аббревиатуры в документации к API были ясны
 *
 */

public class CrptApi {
    private final String API_URL = "'https://ismp.crpt.ru/api/v3/lk/documents/commissioning/contract/create";
    private final String USER_TOKEN = "SOME_TOKEN";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int requestLimit;
    private int requestCount;
    private long lastRequestTime;
    private final long requestInterval;
    private final Lock lock;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit < 1) {
            throw new IllegalArgumentException("Лимит запросов не может быть меньше единицы");
        }

        this.requestLimit = requestLimit;
        this.lastRequestTime = 0;
        this.requestCount = 0;
        this.requestInterval = timeUnit.toMillis(1);
        this.lock = new ReentrantLock();
    }

    private void checkDocumentRequirements(Document document) throws HttpException {
        if (document.products != null) {
            if (document.products.uit_code == null && document.products.uitu_code == null) {
                throw new HttpException("Обязателен один из параметров: uit_code или uitu_code");
            }
            if (document.products.certificate_document != null && (!document.products.certificate_document.equals("CONFORMITY_CERTIFICATE") && !document.products.certificate_document.equals("CONFORMITY_DECLARATION"))) {
                throw new HttpException("Указан неверный вид документа обязательной сертификации");
            }
        }

        if (!document.production_type.equals("OWN_PRODUCTION") && !document.production_type.equals("CONTRACT_PRODUCTION")) {
            throw new HttpException("Указан неверный тип производственного заказа");
        }
    }

    public String createDocument(Document document, String signature) throws HttpException {
        checkDocumentRequirements(document);
        return performApiRequest(document, signature, getDocumentFormat(document));
    }

    private DocumentFormat getDocumentFormat (Document document) throws HttpException {
        if (document.doc_type.equals(Type.LP_INTRODUCE_GOODS.toString())) {
            return DocumentFormat.MANUAL;
        } else if (document.doc_type.equals(Type.LP_INTRODUCE_GOODS_XML.toString())) {
            return DocumentFormat.XML;
        } else if (document.doc_type.equals(Type.LP_INTRODUCE_GOODS_CSV.toString())) {
            return DocumentFormat.CSV;
        } else {
            throw new HttpException("Неверный формат документа");
        }
    }

    private String performApiRequest(Document document, String signature, DocumentFormat documentFormat) throws HttpException {
        lock.lock();
        try {
            long currentTime = System.currentTimeMillis();
            long timeElapsed = currentTime - lastRequestTime;

            if (timeElapsed < requestInterval) {
                try {
                    Thread.sleep(requestInterval - timeElapsed);
                } catch (InterruptedException e) {
                    throw new HttpException("Ошибка при ожидании интервала между запросами", e);
                }
            }

            if (requestCount >= requestLimit) {
                throw new HttpException("Превышен лимит запросов");
            }

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost httpPost = new HttpPost(API_URL);
                httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN);

                List<NameValuePair> parameters = new ArrayList<>(4);
                parameters.add(new BasicNameValuePair("document_format", documentFormat.toString()));
                parameters.add(new BasicNameValuePair("product_document", Base64.getEncoder().encodeToString(objectMapper.writeValueAsString(document).getBytes())));
                parameters.add(new BasicNameValuePair("signature", Base64.getEncoder().encodeToString(signature.getBytes())));
                parameters.add(new BasicNameValuePair("type", document.doc_type));

                httpPost.setEntity(new UrlEncodedFormEntity(parameters, "UTF-8"));

                HttpResponse httpResponse = httpClient.execute(httpPost);

                return handleResponse(httpResponse);
            } catch (IOException e) {
                throw new HttpException("Ошибка при выполнении HTTP-запроса", e);
            } finally {
                lastRequestTime = System.currentTimeMillis();
                requestCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    private String handleResponse(HttpResponse httpResponse) throws IOException {
        try {
            int status = httpResponse.getStatusLine().getStatusCode();

            if (status >= 200 && status < 300) {
                HttpEntity entity = httpResponse.getEntity();
                return entity != null ? EntityUtils.toString(entity) : null;
            }

            Map<String, String> map = new HashMap<>();
            map.put("error message", "Произошла неизвестная ошибка");

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } finally {
            HttpClientUtils.closeQuietly(httpResponse);
        }
    }



    @AllArgsConstructor
    @RequiredArgsConstructor
    @Getter
    @Setter
    public class Products {
        @NonNull
        private String owner_inn;
        @NonNull
        private String producer_inn;
        @NonNull
        private String production_date;
        @NonNull
        private String tnved_code;

        private String certificate_document;
        private String certificate_document_date;
        private String certificate_document_number;
        private String uit_code;
        private String uitu_code;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Products products = (Products) o;
            return Objects.equals(owner_inn, products.owner_inn) && Objects.equals(producer_inn, products.producer_inn) && Objects.equals(production_date, products.production_date) && Objects.equals(tnved_code, products.tnved_code) && Objects.equals(certificate_document, products.certificate_document) && Objects.equals(certificate_document_date, products.certificate_document_date) && Objects.equals(certificate_document_number, products.certificate_document_number) && Objects.equals(uit_code, products.uit_code) && Objects.equals(uitu_code, products.uitu_code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner_inn, producer_inn, production_date, tnved_code, certificate_document, certificate_document_date, certificate_document_number, uit_code, uitu_code);
        }
    }

    @AllArgsConstructor
    @RequiredArgsConstructor
    @Getter
    @Setter
    public class Document {
        @NonNull
        private String doc_id;
        @NonNull
        private String doc_status;
        @NonNull
        private String doc_type;
        @NonNull
        private String owner_inn;
        @NonNull
        private String participant_inn;
        @NonNull
        private String producer_inn;
        @NonNull
        private String production_date;
        @NonNull
        private String production_type;

        private Description description;
        private Products products;
        private String importRequest;
        private String reg_date;
        private String reg_number;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Document document = (Document) o;
            return Objects.equals(doc_id, document.doc_id) && Objects.equals(doc_status, document.doc_status) && Objects.equals(doc_type, document.doc_type) && Objects.equals(owner_inn, document.owner_inn) && Objects.equals(participant_inn, document.participant_inn) && Objects.equals(producer_inn, document.producer_inn) && Objects.equals(production_date, document.production_date) && Objects.equals(production_type, document.production_type) && Objects.equals(description, document.description) && Objects.equals(products, document.products) && Objects.equals(importRequest, document.importRequest) && Objects.equals(reg_date, document.reg_date) && Objects.equals(reg_number, document.reg_number);
        }

        @Override
        public int hashCode() {
            return Objects.hash(doc_id, doc_status, doc_type, owner_inn, participant_inn, producer_inn, production_date, production_type, description, products, importRequest, reg_date, reg_number);
        }
    }
    @RequiredArgsConstructor
    @Getter
    @Setter
    public class Description {
        @NonNull
        private String participantInn;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Description that = (Description) o;
            return Objects.equals(participantInn, that.participantInn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(participantInn);
        }
    }

    public enum DocumentFormat {
        MANUAL,
        CSV,
        XML
    }

    public enum Type {
        LP_INTRODUCE_GOODS,
        LP_INTRODUCE_GOODS_CSV,
        LP_INTRODUCE_GOODS_XML
    }
}

/*

* Тестовая попытка реализовать авторизацию пользователя,
* не до конца понял как внедрить ее в исходный код, так что решил оставить здесь.


// метод получения токена и даты его создания
    @SuppressWarnings("unchecked")
    public Pair<Long, String> getTokenWithDate() throws IOException, HttpException {
        Pair<Long, InputStream> dateInputStreamPair = (Pair<Long, InputStream>) requestAuthorization();

        // проверка, не истек ли срок жизни кэша
        if (System.currentTimeMillis() - dateInputStreamPair.getLeft() >= 60000) {
            throw new HttpException("Время жизни кэша пары uuid - productGroupId истекло");
        }

        JSONObject jsonObject = new JSONObject(dateInputStreamPair.getRight());

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(AUTHENTIFICATION_TOKEN_URL);
            httpPost.addHeader("Content-Type", "application/json");

            List<NameValuePair> parameters = new ArrayList<>(2);
            parameters.add(new BasicNameValuePair("uuid", jsonObject.getString("uuid")));
            parameters.add(new BasicNameValuePair("data", Base64.getEncoder().encodeToString(jsonObject.getString("data").getBytes(StandardCharsets.UTF_8))));

            httpPost.setEntity(new UrlEncodedFormEntity(parameters, "UTF-8"));
            HttpResponse httpResponse = httpClient.execute(httpPost);
            HttpEntity httpEntity = httpResponse.getEntity();

            return new ImmutablePair<>(System.currentTimeMillis(), new JSONObject(httpEntity.getContent()).getString("token"));
        }
    }

    // метод запроса на авторизацию и получения времени создания кэша пары uuid - productGroupId
    public Object requestAuthorization() throws IOException {

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(AUTHORIZATION_REQUEST_URL);
            HttpResponse httpResponse = httpClient.execute(httpGet);
            Long createdCacheDate = System.currentTimeMillis();

            int code = httpResponse.getStatusLine().getStatusCode();
            return code >= 200 && code < 300 ? new ImmutablePair<>(createdCacheDate, httpResponse.getEntity().getContent()) : new ClientProtocolException("Произошла неизвестная ошибка");

        }
    }

 */