package school.sptech;

import java.io.IOException;
import school.sptech.config.Jira;

public class ExemploCreateIssue {

    public static void main(String[] args) {
        // Dados de acesso ao Jira Cloud
        // ATENÇÃO: USE VARIÁVEIS DE AMBIENTE!
        String baseUrl = "URL DO SEU PROJETO"; // ex: https://seu-dominio.atlassian.net
        String email = "E-MAIL DO USUÁRIO COM ACESSO AO PROJETO";
        String apiToken = "TOKEN DE AUTENTICAÇÃO GERADO NO JIRA";

        Jira jira = new Jira(baseUrl, email, apiToken);

        try {
            // Cria uma issue no backlog do projeto informado
            String response = jira.createIssue(
                  "KEY DO PROJETO", // Key do projeto, presente na URL do seu site
                  "Issue criada via Java", // Nome da issue
                  "Task" // Tipo da issue: "Task", "Bug", "Story", etc.
            );

            // Exibe o JSON de resposta com os dados da issue criada
            System.out.println(response);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
