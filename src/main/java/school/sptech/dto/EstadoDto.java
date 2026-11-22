package school.sptech.dto;

// DTO: representa o bloco "estado" do JSON de /geral/resumo
public class EstadoDto {

    private String titulo;
    private String data;

    public EstadoDto() {
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
