# Configuração do CoreTrace com `.env`

## 1. Preparar as configurações

Na raiz do projeto, copie o modelo e edite os valores:

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

Preencha `DB_PASSWORD` com a senha do usuário MySQL que a aplicação deve usar e `JIRA_API_TOKEN` com um token novo. Não compartilhe o `.env` nem o envie ao Git.

O `DB_HOST=127.0.0.1` só serve se o MySQL estiver na mesma máquina e aceitar conexões por loopback. Se estiver em outra máquina/VM, use o endereço privado correto e garanta conectividade/firewall.

## 2. Permissões MySQL

A aplicação só consulta `servidores`, `componentes_perfil` e `componentes`; use um usuário específico e privilégio somente de leitura. Execute como administrador do MySQL e substitua a senha:

```sql
CREATE USER IF NOT EXISTS 'coretrace_app'@'172.17.0.1'
  IDENTIFIED BY 'ESCOLHA_UMA_SENHA_FORTE';
GRANT SELECT ON coretrace.* TO 'coretrace_app'@'172.17.0.1';
FLUSH PRIVILEGES;
```

O host `'172.17.0.1'` acima é baseado no erro apresentado no terminal. Use o endereço de origem que o servidor realmente vê. Não use `'%'` indiscriminadamente; prefira limitar por IP e firewall. Caso o usuário já exista com outra senha, atualize-o com `ALTER USER` antes de testar.

## 3. Compilar e executar

Execute os comandos na raiz do projeto, onde está o `.env`:

```bash
mvn clean package
java -jar target/coretrace.jar
```

O arquivo `.env` é lido pelo `dotenv-java` a partir do diretório de execução. Se rodar o JAR a partir de outra pasta, a aplicação pode não encontrar o arquivo.

## 4. Credenciais AWS / S3

O código mantém o `DefaultCredentialsProvider` do AWS SDK. Em uma EC2, a recomendação é associar uma IAM Role com permissão mínima para ler o objeto S3, em vez de guardar `AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` no `.env`. O arquivo `.env` configura região, bucket e chave do objeto; ele não injeta automaticamente variáveis para o SDK AWS.

Para execução local, configure um perfil AWS em `~/.aws/credentials` ou use uma sessão autenticada pelo AWS CLI.

## 5. Aviso de segurança

O código original continha um token do Jira e o histórico Git do projeto também o inclui. Revogue esse token e gere outro. Apenas removê-lo do arquivo atual não elimina o segredo dos commits antigos nem de JARs já compilados. Depois de trocar o token, reconstrua o JAR e, se o repositório for compartilhado, avalie a limpeza do histórico e a remoção dos artefatos antigos.
