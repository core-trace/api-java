package school.sptech;

import java.io.IOException;
import school.sptech.config.Jira;

public class ExemploCreateIssue {

    public static void main(String[] args) {
        // Dados de acesso ao Jira Cloud
        // ATENÇÃO: USE VARIÁVEIS DE AMBIENTE!
        String baseUrl = "https://petrocontrol.atlassian.net"; // ex: https://seu-dominio.atlassian.net
        String email = "Coretrace.tech@gmail.com";
        String apiToken = "ATATT3xFfGF0uzRbzF-uV29Ct-iyYIFHpsJSSLsiCEYDqgsUkZXvUh7TB4Jz-xPrqa-yk0ZMKBACoc4diMA28pQmx3alRBybWzxdmUkAhp0FYVD9ipWQ8XtLi4a0OvueDKTj8C2KNFWyk4Q2z60aTeCpUGEY2twDLaCi9ik25n4pwRyiR47por4=C706F07D"; // Colocar o token aqui;

        Jira jira = new Jira(baseUrl, email, apiToken);

        try {
            // Cria uma issue no backlog do projeto informado
            String response = jira.createIssue(
                  "SUP", // Key do projeto, presente na URL do seu site
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
