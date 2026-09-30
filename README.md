# Tutorial: Integrando Java com o Jira Cloud via API REST

## 🎯 Objetivo

Neste tutorial, vamos aprender a integrar uma aplicação Java com o **Jira Cloud** usando a API REST
para criar issues automaticamente. Vamos utilizar a classe `HttpClient` para realizar as requisições
HTTP e a biblioteca `Jackson` para converter (serializar) objetos Java em JSON.

A autenticação é feita via **token de API** combinado com o e-mail do usuário, um método seguro que
permite que aplicações externas façam requisições autenticadas sem armazenar senhas. O token funciona
como uma credencial especial que pode ser revogada a qualquer momento.

Para mais informações, consulte a
[documentação oficial da API do Jira](https://developer.atlassian.com/cloud/jira/platform/rest/v3/).

## 📚️ Dependências

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.2</version>
</dependency>
```

## Passo a Passo

### ⚒️ 1. Gerando um Token de API no Jira

Antes de integrar com o código Java, é preciso gerar um token de API:

1. Acesse [jira.atlassian.com](https://jira.atlassian.com) e crie uma conta (ou faça login).
2. Crie um site (workspace) e, dentro dele, crie um projeto (ex: tipo "Software", modelo "Kanban").
3. Copie a URL base do projeto, no formato `https://seu-dominio.atlassian.net`.
4. Clique na sua foto de perfil → "Configurações de perfil" → "Segurança" → "API tokens" →
   "Criar token de API".
5. Defina uma expiração para o token (recomendado por segurança) e copie o valor gerado —
   **ele não será exibido novamente**.

A **key do projeto** aparece na URL do backlog, por exemplo:
`https://java-integration.atlassian.net/jira/software/projects/SCRUM/boards/1/backlog` → a key é
**`SCRUM`**.

### 🧠 2. O que é um DTO?

Um **JSON** é só texto — uma forma padronizada de representar dados (`{"nome": "Rick"}`). Só que em
Java não conseguimos manipular texto solto com segurança: não temos autocomplete, nem checagem de
tipos, nem como chamar `.getName()` num texto.

Por isso criamos uma classe Java cujos atributos têm os mesmos nomes das chaves do JSON. Essa classe
é chamada de **DTO** (*Data Transfer Object* — "objeto de transferência de dados"): ela não tem
regra de negócio nenhuma, só existe para carregar dados no formato do JSON, agora como um objeto
Java de verdade.

A biblioteca **Jackson** é quem faz a conversão nos dois sentidos:

- JSON → objeto Java: `mapper.readValue(json, MinhaClasseDto.class)`
- objeto Java → JSON: `mapper.writeValueAsString(meuObjeto)`

Ou seja: **o DTO é só um "molde" em Java para o formato do JSON.** Se o JSON muda, o DTO muda junto.

No caso do Jira, o JSON de criação de issue tem objetos dentro de objetos (`fields.project.key`,
`fields.issuetype.name`). Por isso, em vez de um DTO só, criamos vários DTOs pequenos —
`ProjectDto`, `IssueTypeDto`, `IssueFieldsDto` e `IssueRequestDto` — cada um espelhando um "nível"
do JSON.

### 🥸 3. Criando os DTOs da Requisição

Para representar o corpo JSON esperado pela API de criação de issues, criamos DTOs que espelham a
estrutura exigida pelo Jira:

```json
{
  "fields": {
    "project": { "key": "" },
    "summary": "",
    "issuetype": { "name": "" }
  }
}
```

* Nota: assim como no consumo de APIs, cada DTO precisa de um construtor vazio e getters/setters para
  que o Jackson consiga serializar o objeto corretamente.

```java
package school.sptech.dto;

public class IssueRequestDto {
    private IssueFieldsDto fields;
    // Construtor, getters e setters...
}
```

`IssueFieldsDto` agrega `ProjectDto` (com a `key` do projeto), o `summary` (título da issue) e
`IssueTypeDto` (com o `name` do tipo, ex: "Task", "Bug", "Story").

### 👉️ 4. Classe `Jira`

A classe `Jira` (em `school.sptech.config`) monta o header de autenticação `Basic` a partir do e-mail
e do token, e expõe o método `createIssue`:

```java
Jira jira = new Jira(baseUrl, email, apiToken);

String response = jira.createIssue(
      "SCRUM",                 // Key do projeto
      "Issue criada via Java", // Resumo
      "Task"                   // Tipo de issue
);

System.out.println(response);
```

**Explicação:**

1. Montamos os DTOs (`ProjectDto`, `IssueTypeDto`, `IssueFieldsDto`, `IssueRequestDto`) com os dados
   recebidos.
2. Serializamos `IssueRequestDto` para JSON com `objectMapper.writeValueAsString`.
3. Construímos a requisição `POST` para `{baseUrl}/rest/api/3/issue`, com o header `Authorization`
   contendo as credenciais em Base64.
4. Enviamos a requisição e retornamos o corpo da resposta caso o status esteja entre 200 e 299;
   caso contrário, lançamos uma exceção com o status e o corpo retornado.

### 🏃 5. Executando o Exemplo

Abra `src/main/java/school/sptech/ExemploCreateIssue.java` e substitua os valores de exemplo:

```java
String baseUrl = "https://seu-dominio.atlassian.net";
String email = "seu-email@example.com";
String apiToken = "seu-token-aqui";
```

Execute a classe `ExemploCreateIssue`. Se a requisição for bem-sucedida, uma nova issue será criada
no backlog do seu projeto e o JSON de resposta (com o ID da issue) será exibido no console.

### 6. Tratamento de Exceções

As operações HTTP e de serialização JSON podem gerar exceções (`IOException`, `InterruptedException`).
Neste exemplo, elas são tratadas em um bloco `try-catch`.

## ⚠️ Segurança

- **Nunca compartilhe seu token de API** publicamente ou suba no GitHub.
- **Nunca commite suas credenciais** no repositório.
- Se o token vazar, acesse imediatamente o Jira e delete/revogue o token, depois gere um novo.

## ➕️ Segue abaixo os links da documentação do Jackson, do HttpClient e do Jira:

- [HttpClient](https://www.baeldung.com/java-9-http-client)
- [Jackson](https://github.com/FasterXML/jackson-docs)
- [Documentação da API REST do Jira Cloud](https://developer.atlassian.com/cloud/jira/platform/rest/v3/)
- [Autenticação no Jira Cloud](https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/)
- [Criando Tokens de API](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/)
