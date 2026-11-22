package school.sptech.dto;

// DTO: representa um item do bloco "acoes" (as ações recomendadas) do JSON de /geral/resumo
public class AcaoDto {

    private String titulo;
    private String destino;

    public AcaoDto() {
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getDestino() {
        return destino;
    }

    public void setDestino(String destino) {
        this.destino = destino;
    }
}
