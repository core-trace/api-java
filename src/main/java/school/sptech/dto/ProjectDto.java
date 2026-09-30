package school.sptech.dto;

// DTO: representa como objeto Java o JSON { "key": "..." } do projeto do Jira
public class ProjectDto {

    private String key;

    public ProjectDto() {
    }

    public ProjectDto(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
